package com.socialflux.audit;

import com.socialflux.events.logging.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Envia os eventos pendentes da outbox ao audit-service, em lote (pelo menos uma vez; o serviço é idempotente por
 * {@code eventId}). O agendamento só roda se o serviço tem {@code @EnableScheduling}; {@link #sendPending()} pode ser
 * chamado direto nos testes.
 * <ul>
 *   <li>Falha de rede/HTTP: o lote fica pendente, soma uma tentativa e o relay espera com backoff exponencial; ao atingir
 *   {@code max-attempts} o evento vira falho ({@code failed_at}) com um WARN.</li>
 *   <li>Evento em {@code rejected}: o audit-service o considera inválido; vira falho na hora, sem reenvio.</li>
 * </ul>
 * Nada que identifique o usuário vai para o log: só contagens, ids de evento e a ação.
 */
public class AuditRelay {

    private static final Logger log = LoggerFactory.getLogger(AuditRelay.class);
    private static final int PURGE_BATCH = 1000;

    private final AuditOutbox outbox;
    private final AuditClient client;
    private final SocialFluxAuditProperties properties;
    private final JsonMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private volatile boolean failing;
    private volatile int consecutiveFailures;
    private volatile Instant notBefore = Instant.MIN;

    public AuditRelay(AuditOutbox outbox, AuditClient client, SocialFluxAuditProperties properties, JsonMapper mapper,
                      Clock clock, PlatformTransactionManager txManager) {
        this.outbox = outbox;
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${socialflux.audit.relay-interval:PT2S}")
    void scheduled() {
        if (clock.instant().isBefore(notBefore)) {
            return;   // em backoff depois de uma falha
        }
        try (CorrelationId.Scope ignored = CorrelationId.open(null)) {
            sendPending();
        } catch (RuntimeException e) {
            // Banco indisponível, por exemplo: não derruba o agendador e tenta de novo.
            reportFailure("Falha ao enviar a auditoria (tentará de novo): {}", e.getClass().getSimpleName());
        }
    }

    /** Envia um lote e devolve quantos eventos o audit-service aceitou (novos ou duplicados). */
    public int sendPending() {
        Integer sent = tx.execute(status -> sendBatch(outbox.lockPending(properties.batchSize())));
        return sent == null ? 0 : sent;
    }

    private int sendBatch(List<AuditOutbox.Row> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<UUID> ids = rows.stream().map(AuditOutbox.Row::id).toList();
        String body = rows.stream().map(AuditOutbox.Row::payload).collect(Collectors.joining(",", "{\"events\":[", "]}"));

        AuditClient.Response response;
        try {
            response = client.send(body);
        } catch (RuntimeException e) {
            return failed(ids, "indisponível: " + e.getClass().getSimpleName());
        }
        if (!response.ok()) {
            return failed(ids, "HTTP " + response.status());
        }
        JsonNode json;
        try {
            json = mapper.readTree(response.body());
        } catch (RuntimeException e) {
            return failed(ids, "resposta ilegível");
        }

        Set<UUID> rejected = rejectEvents(json.path("rejected"), new HashSet<>(ids));
        List<UUID> accepted = ids.stream().filter(id -> !rejected.contains(id)).toList();
        outbox.markSent(accepted);
        recovered();
        log.info("Auditoria: {} eventos enviados ao audit-service ({} duplicados, {} recusados)", accepted.size(),
                json.path("duplicates").asInt(0), rejected.size());
        return accepted.size();
    }

    private Set<UUID> rejectEvents(JsonNode rejectedNode, Set<UUID> batchIds) {
        Set<UUID> rejected = new HashSet<>();
        for (JsonNode item : rejectedNode) {
            UUID id = parseId(item.path("eventId").asString(""));
            if (id == null || !batchIds.contains(id) || !rejected.add(id)) {
                continue;
            }
            outbox.markRejected(id, item.path("reason").asString("sem motivo"));
            log.warn("Auditoria: evento {} recusado pelo audit-service ({}); não será reenviado", id,
                    item.path("reason").asString("sem motivo"));
        }
        return rejected;
    }

    private int failed(List<UUID> ids, String error) {
        List<AuditOutbox.Failure> failures = outbox.recordFailure(ids, error, properties.maxAttempts());
        consecutiveFailures++;
        notBefore = clock.instant().plus(backoff(consecutiveFailures));
        reportFailure("Não foi possível enviar {} evento(s) de auditoria ({}); ficam pendentes", ids.size(), error);
        for (AuditOutbox.Failure failure : failures) {
            if (failure.gaveUp()) {
                log.warn("Auditoria: evento {} marcado como falho após {} tentativas; não será mais reenviado",
                        failure.id(), properties.maxAttempts());
            }
        }
        return 0;
    }

    Duration backoff(int failures) {
        long factor = 1L << Math.min(Math.max(failures - 1, 0), 20);
        Duration wait = properties.retryBackoff().multipliedBy(factor);
        return wait.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : wait;
    }

    private void recovered() {
        consecutiveFailures = 0;
        notBefore = Instant.MIN;
        if (failing) {
            failing = false;
            log.info("Envio da auditoria ao audit-service retomado");
        }
    }

    /** Só a primeira falha sobe como WARN; as repetições ficam em DEBUG até o envio voltar. */
    private void reportFailure(String message, Object... args) {
        if (failing) {
            log.debug(message, args);
            return;
        }
        failing = true;
        log.warn(message, args);
    }

    /** Remove, em lotes, eventos enviados há mais tempo que {@code retention}. Falhos ficam para análise. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    void purge() {
        try (CorrelationId.Scope ignored = CorrelationId.open(null)) {
            Instant limit = clock.instant().minus(properties.retention());
            int total = 0;
            int removed;
            do {
                removed = outbox.deleteSentBefore(limit, PURGE_BATCH);
                total += removed;
            } while (removed == PURGE_BATCH);
            if (total > 0) {
                log.info("Auditoria: {} eventos antigos removidos da outbox", total);
            }
        } catch (RuntimeException e) {
            log.warn("Falha ao limpar a outbox de auditoria: {}", e.getClass().getSimpleName());
        }
    }

    private static UUID parseId(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
