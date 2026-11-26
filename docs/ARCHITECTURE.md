# Arquitetura — socialflux-audit

Biblioteca (jar, sem `main`) com o cliente da auditoria central, compartilhada pelos serviços Spring Boot. Registrada por
auto-configuração; cada serviço é dono da própria tabela `audit_outbox` (migração Flyway dele). Uso e configuração:
[`../README.md`](../README.md).

```
ação do serviço ── AuditRecorder.inTransaction / independent ─► audit_outbox (JSON do evento)
                                                                    │
            AuditRelay (@Scheduled, ~2 s, SKIP LOCKED, lote ≤ 100) ┘─► AuditClient ─POST /v1/events─► audit-service (rede audit-net)
                                                                                     ◄─ {accepted, duplicates, rejected}
```

## Árvore de diretórios

```
socialflux-audit/
├── pom.xml                       parent spring-boot-starter-parent 4.1.2; dependências: starter-jdbc, starter-jackson, spring-web, socialflux-events; testes: embedded-postgres
├── .gitignore                    ignora target/ e arquivos de IDE
├── README.md                     o que a biblioteca entrega, como usar, configuração
├── .github/workflows/ci.yml      CI: instala a socialflux-events e roda os testes
├── docs/ARCHITECTURE.md          este arquivo
└── src/
    ├── main/java/com/socialflux/audit/
    ├── main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/java/com/socialflux/audit/
```

## Arquivos (`com.socialflux.audit`)

| Arquivo | O que representa |
| --- | --- |
| `SocialFluxAuditAutoConfiguration.java` | Auto-configuração: `AuditEventBuilder` sempre; com `enabled=true` a outbox, o recorder, o cliente HTTP e o relay; sem ele o recorder em memória |
| `SocialFluxAuditProperties.java` | Propriedades `socialflux.audit.*`, validadas ao subir (lote 1–100, nome do serviço, url/chave obrigatórias com `enabled=true`) |
| `AuditRecorder.java` | Porta de gravação: `inTransaction`, `independent` e `record(entry, modo)` |
| `AuditEntry.java` | Registro imutável do que auditar, com montagem fluente (`actor`, `target`, `client`, `detail`) e validação da ação |
| `AuditMode.java` / `Outcome.java` / `ActorType.java` | Enums: modo de gravação, resultado (`SUCCESS`/`FAILURE`/`DENIED`) e tipo de ator |
| `AuditEventBuilder.java` | Monta o evento do contrato do audit-service (UUIDv7, instante, serviço, correlação) e filtra o `detail` (chaves de dado pessoal, limite de 4 KB) |
| `OutboxAuditRecorder.java` | Grava em `audit_outbox` com `TransactionTemplate` `MANDATORY` ou `REQUIRES_NEW` |
| `AuditOutbox.java` | SQL da tabela: inserir, travar pendentes, marcar enviados/recusados, contar falhas, apagar enviados antigos em lote |
| `AuditRelay.java` | Envio agendado em lote, backoff global, falhos por recusa ou tentativas esgotadas, retomada, limpeza dos enviados |
| `AuditClient.java` | `RestClient` do audit-service: chave `Bearer`, timeouts curtos, `X-Correlation-Id` |
| `InMemoryAuditRecorder.java` | Recorder de testes/dev: guarda os últimos 1000 eventos em memória; `inTransaction` só registra se a transação confirmar |

## Testes (`src/test/java/com/socialflux/audit/`)

| Arquivo | O que cobre |
| --- | --- |
| `SocialFluxAuditTest.java` | PostgreSQL embutido e audit-service simulado: modos de gravação e rollback, envio com chave e correlação, recusados, backoff e esgotamento, lote, limpeza, filtro do `detail`, validação das propriedades e recorder em memória |
