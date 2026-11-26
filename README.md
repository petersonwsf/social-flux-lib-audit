# socialflux-audit

Biblioteca compartilhada com o **cliente da auditoria central** dos serviços Spring Boot do Social Flux. O serviço grava o
evento de auditoria numa **outbox local** (`audit_outbox`) e um relay o envia em lote ao
[`audit-service`](https://github.com/petersonwsf/audit-service) (`POST /v1/events`). Uma falha ou lentidão do audit-service
nunca derruba a ação do usuário: o evento fica pendente e é reenviado. Spring Boot 4.1 · Java 21 · auto-configuração.

Depende só de `socialflux-events` (para ler o `CorrelationId` e gerar UUIDv7).

## O que ela entrega

| Classe | Quando existe | Para que serve |
| --- | --- | --- |
| `AuditRecorder` | sempre | porta de gravação: `inTransaction(entry)`, `independent(entry)` e `record(entry, modo)` |
| `AuditEntry` | — | o que auditar: `action`, `outcome`, ator, alvo, IP/user agent e `detail` (montagem fluente e imutável) |
| `OutboxAuditRecorder` | `enabled=true` | grava em `audit_outbox`; `inTransaction` usa a transação atual (`MANDATORY`) e `independent` abre outra (`REQUIRES_NEW`) |
| `AuditRelay` | `enabled=true` | `@Scheduled` a cada ~2 s: lote de até 100 com `FOR UPDATE SKIP LOCKED`, retry com backoff, falhos, limpeza |
| `AuditClient` | `enabled=true` | `RestClient` com a chave da aplicação e o `X-Correlation-Id` do fluxo |
| `InMemoryAuditRecorder` | `enabled=false` | guarda os eventos em memória (testes e dev sem audit-service), com a mesma semântica dos modos |
| `AuditEventBuilder` | sempre | monta o evento (UUIDv7, instante, serviço, correlação) e filtra o `detail` |

### Os dois modos

- **`inTransaction`**: o evento entra na transação da ação. Rollback da ação = o evento some junto. Sem transação ativa,
  lança `IllegalTransactionStateException`. Use para o que só aconteceu se tudo deu certo (cadastro, troca de senha...).
- **`independent`**: o evento é confirmado numa transação própria e sobrevive ao rollback de quem chamou. Use para falhas e
  negações (login recusado, reuso de refresh token, conteúdo recusado pela moderação).

## Como usar num serviço

1. Dependência no `pom.xml` (a `socialflux-events` já deve estar lá):
   ```xml
   <dependency>
       <groupId>com.socialflux</groupId>
       <artifactId>socialflux-audit</artifactId>
       <version>0.1.0-SNAPSHOT</version>
   </dependency>
   ```
2. Tabela numa migração **nova** do serviço (nunca edite uma existente):
   ```sql
   create table audit_outbox (
       id         uuid primary key,                       -- eventId (idempotência no audit-service)
       payload    text not null,                          -- JSON do evento
       created_at timestamp with time zone not null,
       sent_at    timestamp with time zone,
       attempts   integer not null default 0,
       last_error varchar(300),
       failed_at  timestamp with time zone                -- recusado ou esgotou as tentativas: não é mais reenviado
   );
   create index idx_audit_outbox_pending on audit_outbox (created_at, id) where sent_at is null and failed_at is null;
   create index idx_audit_outbox_sent on audit_outbox (sent_at) where sent_at is not null;
   ```
3. Configuração no `application.yml`, apontando para as variáveis de ambiente do serviço:
   ```yaml
   socialflux:
     audit:
       enabled: ${AUDIT_ENABLED:false}
       url: ${AUDIT_URL:http://audit-service:8090}
       api-key: ${AUDIT_API_KEY:}
       service-name: profile
   ```
4. `@EnableScheduling` na aplicação (o mesmo do relay de eventos).
5. Gravar:
   ```java
   audit.inTransaction(AuditEntry.success("PROFILE_UPDATED").actor(userId).target("PROFILE", userId)
           .client(client.ip(), client.userAgent()).detail("fields", List.of("nome", "bio")));
   audit.independent(AuditEntry.of("LOGIN_FAILURE", Outcome.FAILURE).actor(accountId));
   ```
6. Testes: sem `enabled`, o bean é um `InMemoryAuditRecorder`; injete-o e confira `recorded()`.

## Regras do evento

- A **aplicação** não vai no corpo: é a da chave (`AUDIT_API_KEY`). O `service` vem de `service-name`.
- O `actorType` vira `USER` quando há `actorId` e `ANONYMOUS` sem ele, salvo se informado.
- **Nunca** grave e-mail, senha, token, telefone, data de nascimento nem texto de postagem: só UUIDs, nomes de campos e
  categorias. O `AuditEventBuilder` descarta (com um WARN) chaves do `detail` que contenham `password`, `senha`, `secret`,
  `token`, `cookie`, `authorization`, `email`, `phone`, `telefone`, `cpf`, `birth` ou `nascimento` (como o audit-service), e
  o `detail` inteiro se passar de 4 KB.
- O `correlationId` do fluxo atual (MDC) vai no evento, ligando-o aos logs do Grafana.

## Entrega

1. O relay pega até `batch-size` pendentes (mais antigos primeiro, `FOR UPDATE SKIP LOCKED`: seguro com várias instâncias) e
   faz o `POST`. Resposta `{accepted, duplicates, rejected}`.
2. Aceitos e duplicados → `sent_at`. Em `rejected` (o audit-service considerou o evento inválido) → `failed_at` na hora, sem
   reenvio.
3. Erro de rede, timeout ou HTTP ≠ 2xx → o lote fica pendente, soma `attempts` e o relay espera com backoff exponencial
   (`retry-backoff` dobrando até `max-backoff`). Ao chegar em `max-attempts`, o evento vira falho (`failed_at`) com um WARN.
   Falhos ficam na tabela para análise.
4. Enviados são removidos após `retention` (em lotes de 1000, a cada hora).

Logs (pt-BR): INFO por lote enviado (contagens), WARN na primeira falha, na recusa e em evento falho, INFO na retomada. Nunca
saem IP, user agent, `detail` nem a chave. O relay abre o próprio escopo de `CorrelationId`.

## Configuração (`socialflux.audit.*`)

| Chave | Padrão | Descrição |
| --- | --- | --- |
| `enabled` | `false` | Liga outbox + relay. Desligado, usa o recorder em memória |
| `url` | — | URL base do audit-service na rede Docker `audit-net` (ex.: `http://audit-service:8090`) |
| `api-key` | — | Chave da aplicação (`ak_...`). Segredo: só no `.env` |
| `service-name` | — | Serviço de origem (`[a-z0-9_-]{2,40}`): `auth`, `profile`, `graph`, `post` |
| `relay-interval` | `PT2S` | Intervalo entre os envios |
| `batch-size` | `100` | Eventos por lote (1 a 100) |
| `max-attempts` | `50` | Tentativas antes de marcar como falho (com o backoff padrão, cerca de 4 horas de audit-service fora do ar) |
| `retry-backoff` / `max-backoff` | `PT2S` / `PT5M` | Espera inicial e teto do backoff |
| `connect-timeout` / `read-timeout` | `PT3S` / `PT10S` | Timeouts do cliente HTTP |
| `retention` | `P7D` | Por quanto tempo os enviados ficam na outbox |

Com `enabled=true`, `url`, `api-key` e `service-name` são obrigatórios (o serviço não sobe sem eles).

## Build e testes

`mvn test` (precisa da `socialflux-events` instalada: `mvn -f ../socialflux-events/pom.xml install`). Os testes usam PostgreSQL
real embutido (zonky, sem Docker) e um audit-service simulado em `com.sun.net.httpserver`. Para os serviços usarem a lib:
`mvn install`.
