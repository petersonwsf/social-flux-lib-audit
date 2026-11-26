package com.socialflux.audit;

/** Como o evento é gravado na outbox local. */
public enum AuditMode {
    /** Na transação atual (exige uma): se ela der rollback, o evento some junto com a ação. */
    IN_TRANSACTION,
    /** Em transação própria ({@code REQUIRES_NEW}): sobrevive ao rollback, para falhas e negações. */
    INDEPENDENT
}
