package com.socialflux.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * O que o serviço quer auditar. Imutável: cada método de montagem devolve uma cópia.
 * <p>Só ids (UUID) e nomes: nunca e-mail, senha, token, telefone, data de nascimento nem texto de postagem. O {@code detail}
 * é filtrado pelo {@link AuditEventBuilder} (chaves de dado pessoal são descartadas), mas a responsabilidade é de quem chama.
 *
 * @param action     ação em {@code [A-Z0-9_.]{2,60}} (ex.: {@code LOGIN_FAILURE})
 * @param outcome    resultado
 * @param actorId    quem agiu (UUID da conta), ou {@code null}
 * @param actorType  tipo do ator; se nulo, vira {@code USER} quando há {@code actorId} e {@code ANONYMOUS} sem ele
 * @param targetType o que foi afetado (ex.: {@code POST}), opcional
 * @param targetId   id do afetado, opcional
 * @param ip         IP do cliente (vai ao audit-service; nunca para o log)
 * @param userAgent  user agent (idem)
 * @param detail     objeto JSON pequeno (até 4 KB), sem dado pessoal
 */
public record AuditEntry(String action, Outcome outcome, UUID actorId, ActorType actorType, String targetType,
                         UUID targetId, String ip, String userAgent, Map<String, Object> detail) {

    private static final Pattern ACTION = Pattern.compile("[A-Z0-9_.]{2,60}");

    public AuditEntry {
        if (action == null || !ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("Ação de auditoria inválida: use [A-Z0-9_.], de 2 a 60 caracteres");
        }
        Objects.requireNonNull(outcome, "outcome");
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    public static AuditEntry of(String action, Outcome outcome) {
        return new AuditEntry(action, outcome, null, null, null, null, null, null, Map.of());
    }

    public static AuditEntry success(String action) {
        return of(action, Outcome.SUCCESS);
    }

    /** Define o autor da ação. */
    public AuditEntry actor(UUID id) {
        return new AuditEntry(action, outcome, id, actorType, targetType, targetId, ip, userAgent, detail);
    }

    public AuditEntry actorType(ActorType type) {
        return new AuditEntry(action, outcome, actorId, type, targetType, targetId, ip, userAgent, detail);
    }

    public AuditEntry target(String type, UUID id) {
        return new AuditEntry(action, outcome, actorId, actorType, type, id, ip, userAgent, detail);
    }

    public AuditEntry client(String ip, String userAgent) {
        return new AuditEntry(action, outcome, actorId, actorType, targetType, targetId, ip, userAgent, detail);
    }

    /** Acrescenta um campo ao {@code detail} (valores simples, listas ou mapas; nunca dado pessoal). Valor nulo é ignorado. */
    public AuditEntry detail(String key, Object value) {
        if (value == null) {
            return this;   // campo sem valor simplesmente não vai
        }
        Map<String, Object> copy = new LinkedHashMap<>(detail);
        copy.put(key, value);
        return new AuditEntry(action, outcome, actorId, actorType, targetType, targetId, ip, userAgent, copy);
    }
}
