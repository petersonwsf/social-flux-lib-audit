package com.socialflux.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recorder usado com {@code socialflux.audit.enabled=false} (testes e desenvolvimento sem audit-service): guarda os eventos
 * em memória, sem banco nem rede. Mantém a semântica dos modos: {@code inTransaction} exige transação e só registra se ela
 * confirmar (rollback descarta); {@code independent} registra na hora. Guarda só os últimos {@value #MAX} eventos.
 */
public class InMemoryAuditRecorder implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAuditRecorder.class);
    private static final int MAX = 1000;

    /** Evento registrado: a entrada original, o modo e o payload que iria ao audit-service. */
    public record Recorded(AuditEntry entry, AuditMode mode, Map<String, Object> payload) {
    }

    private final AuditEventBuilder builder;
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();

    public InMemoryAuditRecorder(AuditEventBuilder builder) {
        this.builder = builder;
    }

    @Override
    public void inTransaction(AuditEntry entry) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                    "Auditoria inTransaction exige uma transação ativa (MANDATORY)");
        }
        Recorded item = new Recorded(entry, AuditMode.IN_TRANSACTION, builder.build(entry).payload());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                add(item);
            }
        });
    }

    @Override
    public void independent(AuditEntry entry) {
        add(new Recorded(entry, AuditMode.INDEPENDENT, builder.build(entry).payload()));
    }

    private void add(Recorded item) {
        if (recorded.size() >= MAX) {
            recorded.remove(0);
        }
        recorded.add(item);
        log.debug("Auditoria {} guardada em memória", item.entry().action());
    }

    /** Cópia dos eventos registrados, do mais antigo ao mais novo. */
    public List<Recorded> recorded() {
        return new ArrayList<>(recorded);
    }

    /** Só os eventos de uma ação. */
    public List<Recorded> recorded(String action) {
        return recorded.stream().filter(item -> item.entry().action().equals(action)).toList();
    }

    public void clear() {
        recorded.clear();
    }
}
