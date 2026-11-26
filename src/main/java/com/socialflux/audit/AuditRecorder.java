package com.socialflux.audit;

/**
 * Porta de gravação da auditoria. O evento vai para a outbox local ({@code audit_outbox}) e o {@link AuditRelay} o envia
 * ao audit-service depois; uma falha do audit-service nunca derruba a ação do usuário.
 */
public interface AuditRecorder {

    /**
     * Grava na transação atual (propagação {@code MANDATORY}: sem transação, falha). Use para o que só aconteceu se a
     * transação der certo (cadastro, troca de senha, exclusão...).
     */
    void inTransaction(AuditEntry entry);

    /**
     * Grava em transação própria ({@code REQUIRES_NEW}): o evento sobrevive ao rollback de quem chamou. Use para falhas e
     * negações (login recusado, reuso de refresh token, conteúdo recusado pela moderação).
     */
    void independent(AuditEntry entry);

    default void record(AuditEntry entry, AuditMode mode) {
        if (mode == AuditMode.INDEPENDENT) {
            independent(entry);
        } else {
            inTransaction(entry);
        }
    }
}
