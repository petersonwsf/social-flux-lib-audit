package com.socialflux.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Configuração da auditoria ({@code socialflux.audit.*}). Cada serviço aponta essas chaves para as suas variáveis de ambiente.
 *
 * @param enabled        liga o envio ao audit-service (outbox + relay). Desligado, os eventos ficam só em memória
 *                       ({@link InMemoryAuditRecorder}), útil em testes e no desenvolvimento sem o audit-service
 * @param url            URL base do audit-service na rede interna (ex.: {@code http://audit-service:8090})
 * @param apiKey         chave da aplicação ({@code ak_...}); nunca vai para log
 * @param serviceName    serviço que gera o evento ({@code [a-z0-9_-]{2,40}}: {@code auth}, {@code profile}...)
 * @param relayInterval  intervalo entre os envios
 * @param batchSize      eventos por lote (1 a 100, o limite do audit-service)
 * @param maxAttempts    tentativas por evento antes de marcá-lo como falho (não é mais reenviado)
 * @param retryBackoff   espera inicial depois de uma falha (dobra a cada falha seguida)
 * @param maxBackoff     teto da espera entre tentativas
 * @param connectTimeout tempo máximo para conectar
 * @param readTimeout    tempo máximo para a resposta
 * @param retention      por quanto tempo eventos já enviados ficam na outbox
 */
@ConfigurationProperties(prefix = "socialflux.audit")
public record SocialFluxAuditProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String url,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String serviceName,
        @DefaultValue("PT2S") Duration relayInterval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("50") int maxAttempts,
        @DefaultValue("PT2S") Duration retryBackoff,
        @DefaultValue("PT5M") Duration maxBackoff,
        @DefaultValue("PT3S") Duration connectTimeout,
        @DefaultValue("PT10S") Duration readTimeout,
        @DefaultValue("P7D") Duration retention) {

    private static final Pattern SERVICE = Pattern.compile("[a-z0-9_-]{2,40}");

    public SocialFluxAuditProperties {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("socialflux.audit.batch-size deve ficar entre 1 e 100");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("socialflux.audit.max-attempts deve ser >= 1");
        }
        if (!serviceName.isBlank() && !SERVICE.matcher(serviceName).matches()) {
            throw new IllegalArgumentException("socialflux.audit.service-name deve ser [a-z0-9_-], de 2 a 40 caracteres");
        }
        if (enabled && (url.isBlank() || apiKey.isBlank() || serviceName.isBlank())) {
            throw new IllegalArgumentException(
                    "socialflux.audit.enabled=true exige url, api-key e service-name preenchidos");
        }
    }

    /** Nome usado no evento; sem configuração (testes), um valor válido qualquer. */
    public String effectiveServiceName() {
        return serviceName.isBlank() ? "unknown" : serviceName;
    }
}
