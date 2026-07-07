# Barrier — contexto do projeto

Plataforma de KYC / PLD-FT para atender às normas do Banco Central. Modelo atual: **motor
de risco** (operador LGPD), evoluindo para plataforma completa. Ver [README](README.md).

## Ao implementar código, siga SEMPRE

- **Padrões de código:** [docs/implementation/coding-standards.md](docs/implementation/coding-standards.md)
- **Plano da Risk Engine:** [docs/implementation/risk-engine-plan.md](docs/implementation/risk-engine-plan.md)
- **Decisões de arquitetura:** [docs/adr/](docs/adr/) (ADR-0009 define o corte atual)

Existe a skill `barrier-implementation` com o checklist operacional — use-a antes de
escrever ou revisar código de implementação.

## Regras que não se negociam

- Camadas: `controller → service → repository`; integração externa só por interface
  (`client`). Validado por ArchUnit.
- Eventos: sempre via **transactional outbox** (nunca publicar direto no Kafka).
- Consumidores idempotentes; Kafka é at-least-once.
- Regras de risco/screening como **Strategy**, com fatores explicáveis.
- Migrations Flyway imutáveis; um schema por serviço.
- Nunca logar CPF/CNPJ sem mascarar; segredos por env.
- Testes: unidade + integração (Testcontainers) + arquitetura (ArchUnit). Bug corrigido
  vem com teste.

## Stack

Java 25 · Spring Boot 4.0 · Maven (monorepo) · PostgreSQL + Flyway · Kafka · MapStruct ·
JUnit 5 / Testcontainers / ArchUnit. Pacote raiz `com.barrier.<contexto>`.

## Estado atual

Multi-tenancy: cada avaliação pertence a um **tenant** (cliente da API). Header `X-Client-Id`
→ `TenantService.resolve` (tabela `tenants`, seed `default`); `assessments.tenant_id` +
`deliveries.tenant_id`; o evento carrega `tenantId`; `GET` é escopado por tenant. Pré-auth
confia no header; quando a API key chegar, o tenant será derivado da key (header ignorado).
Termo `tenant` no código evita colisão com os vários "client" (bureau/HTTP).

Bureaus (identity): cadeia com prioridade (`@Order`) + fallback — bureau indisponível cai
para o próximo; resultado definitivo encerra. CNPJ real via BrasilAPI; CPF no stub.
Watchlists (screening): **ingeridas** (ADR-0010) — `WatchlistImporter` (ApplicationRunner +
@Scheduled) carrega `WatchlistSource`s numa tabela `watchlist_entries`; `LocalWatchlistProvider`
casa por documento. Fonte atual é a semente `resources/watchlists/ceis-seed.csv`; fontes reais
(CGU/OFAC) são novos `WatchlistSource`. Match por nome (fuzzy) é fase seguinte.

Fases 1-4 concluídas (build verde). Fase 1: intake + outbox. Fase 2: Identity.
Fase 3: Screening. Fase 4: motor de risco — `RiskRule` (Strategy) devolve `RiskResult`
padronizado (score/severidade/motivo/evidências/recomendação); `RiskScoringService` soma
0–1000 em bandas LOW/MEDIUM/HIGH/CRITICAL + override, grava `risk_scores` com a versão do
motor (`ENGINE_VERSION`). O `AssessmentProcessor` reúne identidade+screening e delega a
decisão ao motor; fatores explicáveis ficam no assessment (coluna `factors`) e voltam no GET.

Convenções novas: domain do módulo `risk` dividido em subpastas (`domain/enums`,
`domain/model`) — os módulos assessment/identity/screening ainda usam domain plano (retrofit
pendente). Regras de risco: adicionar fonte = adicionar uma `RiskRule`, sem tocar no motor.
`ENGINE_VERSION` deve subir a cada mudança de regra/peso (auditoria).

Webhook API (`services/webhook-api`, pacote `com.barrier.webhook`) concluída: consome
`barrier.assessment.completed`, entrega no endpoint do cliente com HMAC (`HmacSigner`),
retry/backoff (`DeliveryRetryScheduler`), idempotência por `eventId` e rastreio em
`deliveries`. Usa schema Postgres próprio `webhook` (Flyway `schemas=webhook`); escaneia só
`com.barrier.webhook` (não puxa os beans de outbox do commons). Endpoint de destino é config
única (`barrier.webhook.target-url`) — registro por cliente/tenant é evolução futura.

Fase B (mapeada, não implementada): monitoramento contínuo, reavaliação periódica, recálculo
por transação, regra de estrutura societária (KYB), e registro multi-tenant de endpoints.
Próximo: Fase 5 (hardening: OpenAPI, idempotência no intake, mascaramento).

Build validado: `./mvnw test` verde (18 testes, inclui integração com Testcontainers).
JDK local: `C:\Users\leona\.jdks\corretto-25.0.3` (setar `JAVA_HOME` antes do `mvnw`).

Peculiaridades do Spring Boot 4 (aprendidas na prática):
- Autoconfig é modularizada: use `spring-boot-starter-kafka` e `spring-boot-starter-flyway`
  (o `spring-kafka`/`flyway-core` crus NÃO ativam a autoconfiguração nem o `@ServiceConnection`).
- Jackson 3 é o padrão (`tools.jackson.*`), com `java.time` embutido; exceções são unchecked.
- `TestRestTemplate` foi removido — usar `RestClient`/`RestTestClient`.
- `@ServiceConnection` de Kafka suporta `org.testcontainers.kafka.KafkaContainer` (imagem
  apache), não o container clássico nem o `ConfluentKafkaContainer`.
- Testcontainers não é gerenciado pelo BOM do Boot 4 — importar `testcontainers-bom`.
