package com.socialflux.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Acesso à tabela {@code audit_outbox} do serviço (criada na migração dele; ver README da biblioteca). O evento é gravado
 * pronto, como JSON no formato do audit-service, e só sai da tabela depois de {@code retention}.
 */
public class AuditOutbox {

    private static final int MAX_ERROR = 300;

    /** Linha pendente: o JSON do evento e quantas tentativas já falharam. */
    public record Row(UUID id, String payload, int attempts) {
    }

    /** Resultado de registrar uma falha de envio. */
    public record Failure(UUID id, boolean gaveUp) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditOutbox(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void add(UUID id, Instant createdAt, String payload) {
        jdbc.sql("insert into audit_outbox (id, payload, created_at) values (:id, :payload, :createdAt)")
                .param("id", id)
                .param("payload", payload)
                .param("createdAt", at(createdAt))
                .update();
    }

    /** Pendentes mais antigos primeiro, travados contra outras instâncias do relay. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Row> lockPending(int limit) {
        return jdbc.sql("select id, payload, attempts from audit_outbox where sent_at is null and failed_at is null "
                        + "order by created_at, id limit :limit for update skip locked")
                .param("limit", limit)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("payload"),
                        rs.getInt("attempts")))
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markSent(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("update audit_outbox set sent_at = :now, last_error = null where id in (:ids)")
                .param("now", at(clock.instant()))
                .param("ids", ids)
                .update();
    }

    /** O audit-service recusou o evento: vira falho de uma vez, sem reenvio. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markRejected(UUID id, String reason) {
        jdbc.sql("update audit_outbox set failed_at = :now, attempts = attempts + 1, last_error = :error where id = :id")
                .param("now", at(clock.instant()))
                .param("error", cut("recusado: " + reason))
                .param("id", id)
                .update();
    }

    /** Conta a falha de envio; ao atingir {@code maxAttempts} o evento vira falho e não é mais reenviado. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Failure> recordFailure(Collection<UUID> ids, String error, int maxAttempts) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("update audit_outbox set attempts = attempts + 1, last_error = :error, "
                        + "failed_at = case when attempts + 1 >= :max then :now else null end "
                        + "where id in (:ids) returning id, failed_at is not null as gave_up")
                .param("error", cut(error))
                .param("max", maxAttempts)
                .param("now", at(clock.instant()))
                .param("ids", ids)
                .query((rs, n) -> new Failure(rs.getObject("id", UUID.class), rs.getBoolean("gave_up")))
                .list();
    }

    /** Apaga, em um lote, enviados antes do limite; devolve quantos saíram (o chamador repete até vir menos que o lote). */
    public int deleteSentBefore(Instant limit, int batch) {
        return jdbc.sql("delete from audit_outbox where id in (select id from audit_outbox "
                        + "where sent_at is not null and sent_at < :limit limit :batch)")
                .param("limit", at(limit))
                .param("batch", batch)
                .update();
    }

    public long countPending() {
        return jdbc.sql("select count(*) from audit_outbox where sent_at is null and failed_at is null")
                .query(Long.class).single();
    }

    public long countFailed() {
        return jdbc.sql("select count(*) from audit_outbox where failed_at is not null").query(Long.class).single();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String cut(String value) {
        String clean = value.replaceAll("\\p{Cntrl}", " ");
        return clean.length() <= MAX_ERROR ? clean : clean.substring(0, MAX_ERROR);
    }
}
