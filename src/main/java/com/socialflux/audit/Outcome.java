package com.socialflux.audit;

/** Resultado da ação auditada (contrato do audit-service). */
public enum Outcome {
    SUCCESS,
    FAILURE,
    /** Pedido recusado por regra (permissão, moderação, limite). */
    DENIED
}
