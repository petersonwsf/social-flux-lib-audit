package com.socialflux.audit;

/** Quem executou a ação (contrato do audit-service). */
public enum ActorType {
    USER,
    ADMIN,
    SYSTEM,
    ANONYMOUS
}
