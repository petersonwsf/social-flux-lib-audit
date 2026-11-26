package com.socialflux.audit;

import com.socialflux.events.SocialFluxEventsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Registra a auditoria em qualquer serviço que tenha a biblioteca no classpath:
 * <ul>
 *   <li>{@code socialflux.audit.enabled=true}: {@link OutboxAuditRecorder} (tabela {@code audit_outbox}), {@link AuditRelay}
 *   e {@link AuditClient};</li>
 *   <li>desligado (padrão): {@link InMemoryAuditRecorder}, sem banco nem rede.</li>
 * </ul>
 * Cada serviço é dono da própria tabela {@code audit_outbox} (migração Flyway dele).
 */
@AutoConfiguration(after = SocialFluxEventsAutoConfiguration.class)
@EnableConfigurationProperties(SocialFluxAuditProperties.class)
public class SocialFluxAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AuditEventBuilder auditEventBuilder(SocialFluxAuditProperties properties, Clock clock, JsonMapper mapper) {
        return new AuditEventBuilder(properties, clock, mapper);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "socialflux.audit", name = "enabled", havingValue = "true")
    static class Remote {

        @Bean
        @ConditionalOnMissingBean
        AuditOutbox auditOutbox(JdbcClient jdbc, Clock clock) {
            return new AuditOutbox(jdbc, clock);
        }

        @Bean
        @ConditionalOnMissingBean(AuditRecorder.class)
        OutboxAuditRecorder auditRecorder(AuditEventBuilder builder, AuditOutbox outbox,
                                          PlatformTransactionManager txManager) {
            return new OutboxAuditRecorder(builder, outbox, txManager);
        }

        @Bean
        @ConditionalOnMissingBean
        AuditClient auditClient(SocialFluxAuditProperties properties) {
            return new AuditClient(properties);
        }

        @Bean
        @ConditionalOnMissingBean
        AuditRelay auditRelay(AuditOutbox outbox, AuditClient client, SocialFluxAuditProperties properties,
                              JsonMapper mapper, Clock clock, PlatformTransactionManager txManager) {
            return new AuditRelay(outbox, client, properties, mapper, clock, txManager);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "socialflux.audit", name = "enabled", havingValue = "false", matchIfMissing = true)
    static class Local {

        @Bean
        @ConditionalOnMissingBean(AuditRecorder.class)
        InMemoryAuditRecorder auditRecorder(AuditEventBuilder builder) {
            return new InMemoryAuditRecorder(builder);
        }
    }
}
