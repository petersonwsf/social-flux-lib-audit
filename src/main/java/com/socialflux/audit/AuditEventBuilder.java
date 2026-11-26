package com.socialflux.audit;

import com.socialflux.events.EventIds;
import com.socialflux.events.logging.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Monta o evento no formato do {@code POST /v1/events} do audit-service: id UUIDv7, instante, serviço, id de correlação do
 * fluxo atual e {@code detail} já filtrado (chaves de dado pessoal e detalhes grandes são descartados, com um WARN).
 */
public class AuditEventBuilder {

    private static final Logger log = LoggerFactory.getLogger(AuditEventBuilder.class);

    /** Mesmas palavras que o audit-service recusa em chaves do {@code detail}. */
    private static final List<String> FORBIDDEN_KEY_PARTS = List.of("password", "senha", "secret", "token", "cookie",
            "authorization", "email", "phone", "telefone", "cpf", "birth", "nascimento");
    private static final int MAX_DETAIL_BYTES = 4096;
    private static final int MAX_USER_AGENT = 300;
    private static final int MAX_IP = 64;

    /** Evento pronto: o mapa serve ao recorder em memória; o JSON, à outbox. */
    public record Built(UUID id, Instant occurredAt, Map<String, Object> payload, String json) {
    }

    private final SocialFluxAuditProperties properties;
    private final Clock clock;
    private final JsonMapper mapper;

    public AuditEventBuilder(SocialFluxAuditProperties properties, Clock clock, JsonMapper mapper) {
        this.properties = properties;
        this.clock = clock;
        this.mapper = mapper;
    }

    public Built build(AuditEntry entry) {
        UUID id = EventIds.v7(clock);
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", id.toString());
        payload.put("occurredAt", occurredAt.toString());
        payload.put("service", properties.effectiveServiceName());
        payload.put("action", entry.action());
        payload.put("outcome", entry.outcome().name());
        if (entry.actorId() != null) {
            payload.put("actorId", entry.actorId().toString());
        }
        ActorType actorType = entry.actorType() != null ? entry.actorType()
                : entry.actorId() != null ? ActorType.USER : ActorType.ANONYMOUS;
        payload.put("actorType", actorType.name());
        if (entry.targetType() != null) {
            payload.put("targetType", entry.targetType());
        }
        if (entry.targetId() != null) {
            payload.put("targetId", entry.targetId().toString());
        }
        putIfPresent(payload, "ip", cut(entry.ip(), MAX_IP));
        putIfPresent(payload, "userAgent", cut(entry.userAgent(), MAX_USER_AGENT));
        putIfPresent(payload, "correlationId", CorrelationId.current());
        payload.put("schemaVersion", 1);
        Map<String, Object> detail = cleanDetail(entry);
        if (!detail.isEmpty()) {
            payload.put("detail", detail);
        }
        return new Built(id, occurredAt, payload, mapper.writeValueAsString(payload));
    }

    private Map<String, Object> cleanDetail(AuditEntry entry) {
        if (entry.detail().isEmpty()) {
            return Map.of();
        }
        int[] dropped = {0};
        Map<String, Object> clean = cleanMap(entry.detail(), dropped);
        if (dropped[0] > 0) {
            log.warn("Auditoria {}: {} campo(s) do detail descartado(s) por parecerem dado pessoal", entry.action(),
                    dropped[0]);
        }
        if (mapper.writeValueAsString(clean).getBytes(StandardCharsets.UTF_8).length > MAX_DETAIL_BYTES) {
            log.warn("Auditoria {}: detail maior que {} bytes descartado", entry.action(), MAX_DETAIL_BYTES);
            return Map.of();
        }
        return clean;
    }

    private static Map<String, Object> cleanMap(Map<?, ?> source, int[] dropped) {
        Map<String, Object> clean = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (isForbidden(name)) {
                dropped[0]++;
            } else {
                clean.put(name, cleanValue(value, dropped));
            }
        });
        return clean;
    }

    private static Object cleanValue(Object value, int[] dropped) {
        if (value instanceof Map<?, ?> map) {
            return cleanMap(map, dropped);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> clean = new ArrayList<>();
            collection.forEach(item -> clean.add(cleanValue(item, dropped)));
            return clean;
        }
        return value;
    }

    private static boolean isForbidden(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return FORBIDDEN_KEY_PARTS.stream().anyMatch(lower::contains);
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
