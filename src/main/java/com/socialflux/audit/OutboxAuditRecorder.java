package com.socialflux.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Grava a auditoria na outbox local ({@code audit_outbox}). {@code inTransaction} usa a transação de quem chamou
 * ({@code MANDATORY}: sem ela, {@code IllegalTransactionStateException}); {@code independent} abre uma própria
 * ({@code REQUIRES_NEW}) e confirma na saída, então sobrevive ao rollback da transação externa.
 */
public class OutboxAuditRecorder implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(OutboxAuditRecorder.class);

    private final AuditEventBuilder builder;
    private final AuditOutbox outbox;
    private final TransactionTemplate mandatory;
    private final TransactionTemplate requiresNew;

    public OutboxAuditRecorder(AuditEventBuilder builder, AuditOutbox outbox, PlatformTransactionManager txManager) {
        this.builder = builder;
        this.outbox = outbox;
        this.mandatory = new TransactionTemplate(txManager);
        this.mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void inTransaction(AuditEntry entry) {
        mandatory.executeWithoutResult(status -> store(entry));
    }

    @Override
    public void independent(AuditEntry entry) {
        requiresNew.executeWithoutResult(status -> store(entry));
    }

    private void store(AuditEntry entry) {
        AuditEventBuilder.Built event = builder.build(entry);
        outbox.add(event.id(), event.occurredAt(), event.json());
        log.debug("Auditoria {} gravada na outbox (evento {})", entry.action(), event.id());
    }
}
