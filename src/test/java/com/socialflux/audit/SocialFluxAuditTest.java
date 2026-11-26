package com.socialflux.audit;

import com.socialflux.events.SocialFluxEventsAutoConfiguration;
import com.socialflux.events.logging.CorrelationId;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Recorder, outbox, relay e auto-configuração contra um PostgreSQL real (embutido) e um audit-service simulado. */
class SocialFluxAuditTest {

    private static EmbeddedPostgres postgres;
    private static HttpServer server;
    private static final List<Received> received = new CopyOnWriteArrayList<>();
    private static volatile int status = 200;
    private static volatile String responseBody = "{\"accepted\":0,\"duplicates\":0,\"rejected\":[]}";

    private record Received(String authorization, String correlationId, String body) {
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SocialFluxEventsAutoConfiguration.class,
                        SocialFluxAuditAutoConfiguration.class))
                .withUserConfiguration(Infra.class)
                .withPropertyValues("socialflux.audit.enabled=true",
                        "socialflux.audit.url=http://localhost:" + server.getAddress().getPort(),
                        "socialflux.audit.api-key=ak_chave_de_teste",
                        "socialflux.audit.service-name=auth",
                        "socialflux.audit.max-attempts=2");
    }

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        // Mesmo esquema que cada serviço cria na própria migração.
        JdbcClient jdbc = JdbcClient.create(postgres.getPostgresDatabase());
        jdbc.sql("""
                create table audit_outbox (
                    id         uuid primary key,
                    payload    text not null,
                    created_at timestamp with time zone not null,
                    sent_at    timestamp with time zone,
                    attempts   integer not null default 0,
                    last_error varchar(300),
                    failed_at  timestamp with time zone)""").update();
        jdbc.sql("create index idx_audit_outbox_pending on audit_outbox (created_at, id) "
                + "where sent_at is null and failed_at is null").update();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/events", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Correlation-Id"), body));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() throws IOException {
        server.stop(0);
        postgres.close();
    }

    @BeforeEach
    void clean() {
        JdbcClient.create(postgres.getPostgresDatabase()).sql("delete from audit_outbox").update();
        received.clear();
        status = 200;
        responseBody = "{\"accepted\":0,\"duplicates\":0,\"rejected\":[]}";
    }

    @AfterEach
    void closeScope() {
        assertThat(CorrelationId.current()).isNull();
    }

    private static void inTx(AssertableApplicationContext ctx, Runnable action) {
        new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class)).executeWithoutResult(s -> action.run());
    }

    private static long count(String where) {
        return JdbcClient.create(postgres.getPostgresDatabase())
                .sql("select count(*) from audit_outbox where " + where).query(Long.class).single();
    }

    @Test
    void inTransactionRequiresATransactionAndFollowsItsRollback() {
        runner().run(ctx -> {
            AuditRecorder recorder = ctx.getBean(AuditRecorder.class);
            AuditEntry entry = AuditEntry.success("REGISTER").actor(UUID.randomUUID());

            assertThatThrownBy(() -> recorder.inTransaction(entry)).isInstanceOf(IllegalTransactionStateException.class);

            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class))
                    .executeWithoutResult(s -> {
                        recorder.inTransaction(entry);
                        throw new IllegalStateException("falha depois de auditar");
                    })).isInstanceOf(IllegalStateException.class);
            assertThat(count("true")).isZero();

            inTx(ctx, () -> recorder.inTransaction(entry));
            assertThat(count("sent_at is null")).isEqualTo(1);
        });
    }

    @Test
    void independentSurvivesTheRollbackOfTheCallerTransaction() {
        runner().run(ctx -> {
            AuditRecorder recorder = ctx.getBean(AuditRecorder.class);
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class))
                    .executeWithoutResult(s -> {
                        recorder.independent(AuditEntry.of("LOGIN_FAILURE", Outcome.FAILURE));
                        throw new IllegalStateException("operação principal falhou");
                    })).isInstanceOf(IllegalStateException.class);

            assertThat(count("true")).isEqualTo(1);
            // Também funciona sem transação nenhuma.
            recorder.record(AuditEntry.of("REFRESH_REUSE", Outcome.DENIED), AuditMode.INDEPENDENT);
            assertThat(count("true")).isEqualTo(2);
        });
    }

    @Test
    void relaySendsTheBatchWithTheKeyAndMarksItAsSent() {
        runner().run(ctx -> {
            AuditRecorder recorder = ctx.getBean(AuditRecorder.class);
            UUID actor = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            try (CorrelationId.Scope ignored = CorrelationId.open("fluxo-de-teste-1")) {
                inTx(ctx, () -> {
                    recorder.inTransaction(AuditEntry.success("POST_DELETED").actor(actor).target("POST", target)
                            .client("203.0.113.9", "Navegador/1.0").detail("deletedBy", "OWNER"));
                    recorder.inTransaction(AuditEntry.success("LOGOUT"));
                });
            }
            responseBody = "{\"accepted\":2,\"duplicates\":0,\"rejected\":[]}";

            try (CorrelationId.Scope ignored = CorrelationId.open("relay-de-teste-1")) {   // como o @Scheduled faz
                assertThat(ctx.getBean(AuditRelay.class).sendPending()).isEqualTo(2);
            }

            assertThat(received).hasSize(1);
            assertThat(received.get(0).authorization()).isEqualTo("Bearer ak_chave_de_teste");
            assertThat(received.get(0).correlationId()).isEqualTo("relay-de-teste-1");
            JsonNode events = JsonMapper.builder().build().readTree(received.get(0).body()).path("events");
            assertThat(events).hasSize(2);
            JsonNode first = events.get(0);
            assertThat(first.path("service").asString()).isEqualTo("auth");
            assertThat(first.path("action").asString()).isEqualTo("POST_DELETED");
            assertThat(first.path("outcome").asString()).isEqualTo("SUCCESS");
            assertThat(first.path("actorId").asString()).isEqualTo(actor.toString());
            assertThat(first.path("actorType").asString()).isEqualTo("USER");
            assertThat(first.path("targetId").asString()).isEqualTo(target.toString());
            assertThat(first.path("correlationId").asString()).isEqualTo("fluxo-de-teste-1");
            assertThat(first.path("detail").path("deletedBy").asString()).isEqualTo("OWNER");
            assertThat(events.get(1).path("actorType").asString()).isEqualTo("ANONYMOUS");
            assertThat(count("sent_at is not null")).isEqualTo(2);
            assertThat(ctx.getBean(AuditRelay.class).sendPending()).isZero();
            assertThat(received).hasSize(1);
        });
    }

    @Test
    void rejectedEventsBecomeFailedWithoutResend() {
        runner().run(ctx -> {
            AuditRecorder recorder = ctx.getBean(AuditRecorder.class);
            inTx(ctx, () -> {
                recorder.inTransaction(AuditEntry.success("REGISTER"));
                recorder.inTransaction(AuditEntry.success("LOGOUT"));
            });
            UUID rejected = JdbcClient.create(postgres.getPostgresDatabase())
                    .sql("select id from audit_outbox order by created_at, id limit 1").query(UUID.class).single();
            responseBody = "{\"accepted\":1,\"duplicates\":0,\"rejected\":[{\"eventId\":\"" + rejected
                    + "\",\"reason\":\"acao_invalida\"}]}";

            assertThat(ctx.getBean(AuditRelay.class).sendPending()).isEqualTo(1);

            assertThat(count("failed_at is not null and sent_at is null and last_error like 'recusado:%'")).isEqualTo(1);
            assertThat(count("sent_at is not null")).isEqualTo(1);
            assertThat(ctx.getBean(AuditRelay.class).sendPending()).isZero();
            assertThat(received).hasSize(1);
        });
    }

    @Test
    void failuresKeepTheEventPendingBackOffAndGiveUpAtMaxAttempts() {
        runner().run(ctx -> {
            inTx(ctx, () -> ctx.getBean(AuditRecorder.class).inTransaction(AuditEntry.success("REGISTER")));
            AuditRelay relay = ctx.getBean(AuditRelay.class);
            status = 503;

            assertThat(relay.sendPending()).isZero();
            assertThat(count("sent_at is null and failed_at is null and attempts = 1")).isEqualTo(1);

            // Em backoff, o agendador não bate no serviço de novo.
            relay.scheduled();
            assertThat(received).hasSize(1);

            assertThat(relay.sendPending()).isZero();           // 2ª tentativa = max-attempts
            assertThat(count("failed_at is not null and last_error = 'HTTP 503'")).isEqualTo(1);
            assertThat(relay.sendPending()).isZero();
            assertThat(received).hasSize(2);
        });
    }

    @Test
    void sendsNoMoreThanTheBatchSizeAndRecoversAfterAnOutage() {
        runner().withPropertyValues("socialflux.audit.batch-size=2").run(ctx -> {
            AuditRecorder recorder = ctx.getBean(AuditRecorder.class);
            inTx(ctx, () -> {
                for (int i = 0; i < 3; i++) {
                    recorder.inTransaction(AuditEntry.success("LOGOUT"));
                }
            });
            AuditRelay relay = ctx.getBean(AuditRelay.class);
            status = 500;
            relay.sendPending();
            status = 200;
            responseBody = "{\"accepted\":2,\"duplicates\":0,\"rejected\":[]}";

            assertThat(relay.sendPending()).isEqualTo(2);
            assertThat(JsonMapper.builder().build().readTree(received.get(1).body()).path("events")).hasSize(2);
            assertThat(count("sent_at is null and failed_at is null")).isEqualTo(1);
        });
    }

    @Test
    void purgeRemovesOldSentEventsOnly() {
        runner().run(ctx -> {
            inTx(ctx, () -> {
                ctx.getBean(AuditRecorder.class).inTransaction(AuditEntry.success("REGISTER"));
                ctx.getBean(AuditRecorder.class).inTransaction(AuditEntry.success("LOGOUT"));
            });
            responseBody = "{\"accepted\":2,\"duplicates\":0,\"rejected\":[]}";
            ctx.getBean(AuditRelay.class).sendPending();
            JdbcClient jdbc = JdbcClient.create(postgres.getPostgresDatabase());
            // Um dos enviados fica antigo (10 dias); o outro continua dentro da retenção de 7 dias.
            jdbc.sql("update audit_outbox set sent_at = now() - interval '10 days' where id = "
                    + "(select id from audit_outbox order by created_at, id limit 1)").update();
            // Um falho antigo não é apagado: fica para análise.
            jdbc.sql("insert into audit_outbox (id, payload, created_at, failed_at) values (:id, '{}', "
                    + "now() - interval '30 days', now() - interval '30 days')").param("id", UUID.randomUUID()).update();

            ctx.getBean(AuditRelay.class).purge();

            assertThat(count("sent_at is not null")).isEqualTo(1);
            assertThat(count("failed_at is not null")).isEqualTo(1);
        });
    }

    @Test
    void detailDropsPersonalKeysAndOversizedContent() {
        runner().run(ctx -> {
            AuditEventBuilder builder = ctx.getBean(AuditEventBuilder.class);
            AuditEventBuilder.Built built = builder.build(AuditEntry.success("PROFILE_UPDATED")
                    .detail("fields", List.of("nome", "bio"))
                    .detail("email", "a@b.c")
                    .detail("novaSenha", "x")
                    .detail("nested", java.util.Map.of("authToken", "t", "ok", 1)));

            JsonNode detail = JsonMapper.builder().build().readTree(built.json()).path("detail");
            assertThat(detail.has("fields")).isTrue();
            assertThat(detail.has("email")).isFalse();
            assertThat(detail.has("novaSenha")).isFalse();
            assertThat(detail.path("nested").has("authToken")).isFalse();
            assertThat(detail.path("nested").path("ok").asInt()).isEqualTo(1);

            AuditEventBuilder.Built big = builder.build(AuditEntry.success("PROFILE_UPDATED")
                    .detail("texto", "x".repeat(5000)));
            assertThat(JsonMapper.builder().build().readTree(big.json()).has("detail")).isFalse();
        });
    }

    @Test
    void rejectsInvalidActionsAndSettings() {
        assertThatThrownBy(() -> AuditEntry.success("login falhou")).isInstanceOf(IllegalArgumentException.class);
        runner().withPropertyValues("socialflux.audit.url=", "socialflux.audit.service-name=Auth")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner().withPropertyValues("socialflux.audit.batch-size=101").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void disabledUsesTheInMemoryRecorderWithTheSameSemantics() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SocialFluxEventsAutoConfiguration.class,
                        SocialFluxAuditAutoConfiguration.class))
                .withUserConfiguration(Infra.class)
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(AuditRelay.class).doesNotHaveBean(AuditOutbox.class);
                    InMemoryAuditRecorder recorder = ctx.getBean(InMemoryAuditRecorder.class);

                    assertThatThrownBy(() -> recorder.inTransaction(AuditEntry.success("REGISTER")))
                            .isInstanceOf(IllegalTransactionStateException.class);
                    assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class))
                            .executeWithoutResult(s -> {
                                recorder.inTransaction(AuditEntry.success("REGISTER"));
                                throw new IllegalStateException("rollback");
                            })).isInstanceOf(IllegalStateException.class);
                    assertThat(recorder.recorded()).isEmpty();

                    inTx(ctx, () -> recorder.inTransaction(AuditEntry.success("LOGOUT")));
                    recorder.independent(AuditEntry.of("LOGIN_FAILURE", Outcome.FAILURE));
                    assertThat(recorder.recorded()).extracting(r -> r.entry().action())
                            .containsExactly("LOGOUT", "LOGIN_FAILURE");
                    assertThat(recorder.recorded("LOGIN_FAILURE").get(0).payload().get("service")).isEqualTo("unknown");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Infra {

        @Bean
        DataSource dataSource() {
            return postgres.getPostgresDatabase();
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
