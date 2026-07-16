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

Subjects (ADR-0011): o cliente final (CPF/CNPJ) é um **subject global** — 1 registro por
documento (`subjects`, UNIQUE). A visibilidade é por associação (`tenant_subjects`): o `POST`
acha-ou-cria o subject e garante o vínculo; `GET /v1/subjects/{documento}` só retorna se o
tenant tem vínculo (senão 404 — não vaza cliente de outra empresa). `assessments.subject_id`
liga a avaliação ao subject. Decisão de aceitar/recusar (EM_REVISAO) é **por tenant no
assessment**, nunca no subject. Cache compartilhado de dados objetivos entre tenants = futuro
opt-in.

Revisão manual (EDD): avaliação em `EM_REVISAO` é decidida por humano via
`POST /v1/assessments/{id}/decision` (`ReviewDecisionRequest`: APPROVE/REJECT + `reviewedBy` +
`reason`), escopado por tenant. `Assessment.decide` só vale a partir de EM_REVISAO (senão 409),
grava a trilha (`reviewed_by`/`review_reason`/`reviewed_at`, migration V013) e reemite
`barrier.assessment.completed` via `AssessmentEventPublisher` (o webhook entrega o desfecho
final). A resposta e o `GET` expõem a trilha de review.

Multi-tenancy: cada avaliação pertence a um **tenant** (cliente da API). Header `X-Client-Id`
→ `TenantService.resolve` (tabela `tenants`, seed `default`); `assessments.tenant_id` +
`deliveries.tenant_id`; o evento carrega `tenantId`; `GET` é escopado por tenant. Pré-auth
confia no header; quando a API key chegar, o tenant será derivado da key (header ignorado).
Termo `tenant` no código evita colisão com os vários "client" (bureau/HTTP).

Bureaus (identity): cadeia com prioridade (`@Order`) + fallback — bureau indisponível cai
para o próximo; resultado definitivo encerra. CNPJ real via BrasilAPI; CPF no stub. O
BrasilAPI agora também extrai um `CompanyProfile` (abertura/CNAE/QSA) — transiente, não
persistido; `IdentityService.verify` devolve `IdentityResult(check, company)` e o perfil trafega
até o motor de risco pelo `RiskContext`. Regras de PJ que consomem isso: `NewCompanyRiskRule`
(empresa recém-aberta), `SensitiveCnaeRiskRule` (CNAE sensível a PLD-FT) e
`CorporateStructureRiskRule` (KYB — sócio estrangeiro/PJ no QSA de 1º grau; árvore até 3º grau
ainda depende de provedor KYB). `ENGINE_VERSION` = `barrier-risk-rules/1.1.0`.

Watchlists (screening): **ingeridas** (ADR-0010) — `WatchlistImporter` (ApplicationRunner +
@Scheduled) carrega `WatchlistSource`s numa tabela `watchlist_entries`; `LocalWatchlistProvider`
casa por documento (exato). Fontes: semente `resources/watchlists/ceis-seed.csv`; CGU real
(`CeisWatchlistSource`/`CnepWatchlistSource` — baixam o ZIP do Portal da Transparência, parseiam
CSV ISO-8859-1 `;`); OFAC (`OfacWatchlistSource` — `sdn.csv` + `alt.csv`, entradas por nome sem
documento, apelidos viram linhas próprias). Fontes que baixam são gated por config
(`barrier.watchlist.cgu.enabled` / `.ofac.enabled`, **off por padrão** — dev/testes não baixam).
Match por **nome (fuzzy)**: `FuzzyNameWatchlistProvider` (Jaro-Winkler + `NameNormalizer` sobre
as entradas sem documento; limiar/`min-name-length` configuráveis). Evolução: índice/blocking
para volumes grandes do OFAC.

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

Fase B (mapeada, parcial): KYB de 1º grau já ativo (ver acima); pendentes: monitoramento
contínuo, reavaliação periódica, recálculo por transação, navegação do QSA até 3º grau (provedor
KYB dedicado), e registro multi-tenant de endpoints.

Cadastro (CMN 4.753, ADR-0012): `SubjectProfile` (pacote `subject.profile`) é o cadastro
completo do subject — 1:1 (`subject_profiles.subject_id UNIQUE`), separado do `Subject` (que
continua sendo só a identidade mínima para dedup). Campos nullable divididos por tipo (PF:
`birthDate`/`nationality`/`occupation`; PJ: `foundingDate`/`cnaeCode`/`shareCapital`/
`legalRepresentative*`/`partners`); `partners` serializado em `partners_json` (mesmo padrão de
`hits_json`/`results_json`). Cadastro é progressivo: `PUT /v1/subjects/{document}/profile`
aceita atualização parcial a qualquer momento (`SubjectProfilePatch.applyTo` mescla, campo nulo
preserva o existente). `RegistrationCompleteness.evaluate(documentType, profile)` é o checklist
mínimo por tipo — `AssessmentProcessor` consulta depois do score de risco e rebaixa
`APROVADO` → `EM_REVISAO` (com fator explicando os campos faltantes) se o cadastro estiver
incompleto; reaproveita o workflow humano de decisão já existente, sem status novo. Os dados
objetivos de PJ do bureau (`CompanyProfile` — antes descartados) agora são persistidos no
`SubjectProfile` assim que `IdentityService.verify` retorna.

Watchlist em produção (ADR-0013): `WatchlistReadinessGuard` (`ApplicationRunner`) falha o
startup (`IllegalStateException`) se o profile `prod` estiver ativo e a única fonte de
watchlist presente for a `SEED` — evita subir silenciosamente em produção sem CGU/OFAC
habilitados. `application-prod.yml` já habilita `barrier.watchlist.cgu.enabled` e
`.ofac.enabled` por padrão nesse profile.

Bureau real de CPF (ADR-0014): `BigBoostBureauProvider` (`@Order(20)`, entre `BrasilApiBureauProvider`
`=10` e `StubBureauProvider` `=100`) chama o dataset `basic_data` da BigBoost/BigDataCorp
(`POST /pessoas`, headers `AccessToken`/`TokenId`) — self-service, sem CNPJ necessário para
contratar (ao contrário do Serpro). Desligado por padrão
(`barrier.identity.bigboost.enabled=false`); credenciais via `BIGBOOST_ACCESS_TOKEN`/
`BIGBOOST_TOKEN_ID`. `Result` vazio → NOT_FOUND, não-vazio → MATCH (status do CPF na Receita
para MISMATCH ainda não mapeado — campo exato não confirmado 

Registry de regras de risco: `RiskRule.code()` é o código estável da família da regra
(`NEW_COMPANY`, `SANCTION` etc.) — independente do `ruleCode` granular que `RiskResult` pode
variar por desfecho (ex.: `IdentityRiskRule` devolve `IDENTITY_NOT_FOUND`/`IDENTITY_MISMATCH`/
`IDENTITY_UNAVAILABLE`, mas a família é `IDENTITY`). `risk_rule_registry` (migration V015)
guarda o estado operacional de cada família — `enabled`, vigência (`valid_from`/`valid_until`)
e `criticality` (INFO/ALERT/REVIEW/BLOCK, informativa) — editável sem deploy via
`PUT`/`GET /v1/risk-rules` (`RiskRuleRegistryController`). `RiskRuleRegistryService.isActive`
é fail-open: regra sem linha no registry fica ativa (o registry é kill switch/vigência, não
allowlist). `RiskScoringService` filtra as regras pelo registry antes de avaliar — diferente do
override por tenant (`tenant_risk_config`, que ajusta parâmetro de uma regra já ativa por
parceiro), isto liga/desliga a regra inteira, globalmente, para todos os tenants.

Regras de risco configuráveis por tenant: `tenant_risk_config` (migration V015, chave composta
`tenant_id`/`rule_code`/`param_key`) guarda overrides por parceiro; `TenantRiskConfigService`
(pacote `tenant.config.service`) lê o override ou cai no default global (o mesmo `@Value` de
antes). Só regras de apetite de risco — não regulatórias — são configuráveis:
`NewCompanyRiskRule` (`months`/`score`) e `SensitiveCnaeRiskRule` (`cnae-codes`, que só é
**unido** ao default, nunca substituído/`score`). Bandas de score, `IdentityRiskRule`,
`PepRiskRule`/`SanctionRiskRule` (risco) e `PepMatchRule`/`SanctionMatchRule` (screening)
continuam fixas — ArchUnit (`regras_fixas_nao_dependem_de_config_por_tenant`) barra essas
classes de depender de `TenantRiskConfigService`. `RiskContext` ganhou `tenantId`
(`AssessmentProcessor` preenche a partir de `Assessment.tenantId()`); o parâmetro efetivamente
usado por uma regra disparada entra na evidência (`config:months=`/`config:score=`) —
`ENGINE_VERSION` continua só sobre código/algoritmo, não sobre config de tenant. Gestão via
`PUT`/`GET /v1/tenants/{tenantId}/risk-config` (`TenantRiskConfigController`), validado por
`TenantRiskConfigValidator` (allowlist de `rule_code`/`param_key` + ranges); é operação
interna/admin, não self-service do parceiro — deixar o próprio tenant relaxar seus controles é
o risco que a validação existe para evitar (sem gate de admin-auth dedicado ainda, mesma
pré-auth por header do resto da API). Decisão de não separar isso (nem o cadastro PF/PJ já
existente) em serviço novo: ADR-0009 (monólito modular, split incremental por gatilho real).

Mídia negativa: `MatchType.ADVERSE_MEDIA` (já existia, sem uso) ganhou a cadeia completa —
`NegativeMediaProvider` (marca de `WatchlistProvider`, mesma interface de busca) +
`StubNegativeMediaProvider` (casa nome contra CSV `barrier.negative-media.flagged-names`, vazio
por padrão — sem falso positivo em dev; troca por BigBoost/LexisNexis/Dow Jones é só nova
implementação da interface) + `AdverseMediaMatchRule` (screening, filtra o apontamento, mesmo
padrão de `PepMatchRule`/`SanctionMatchRule`) + `NegativeMediaRiskRule` (risco, força REVIEW
como PEP — apontamento de mídia pode ser homônimo/desatualizado, exige julgamento de analista
antes de reprovar). Nenhuma mudança em `ScreeningService`/`RiskScoringService`: os dois já
agregam qualquer bean das interfaces `WatchlistProvider`/`RiskRule`.

Próximo: Fase 5 (hardening: OpenAPI, idempotência no intake, mascaramento) e o backlog de
compliance da Fase 6 (COAF/SISCOAF, retenção de 10 anos, criptografia em repouso, UBO além do
1º grau, bureau real de CPF) — ver `docs/implementation/risk-engine-plan.md`.

Build validado: `./mvnw test` verde (78 testes, inclui integração com Testcontainers).
JDK local: `C:\Users\leona\.jdks\corretto-25.0.3` (setar `JAVA_HOME` antes do `mvnw`).

Peculiaridades do Spring Boot 4 (aprendidas na prática):
- Autoconfig é modularizada: use `spring-boot-starter-kafka` e `spring-boot-starter-flyway`
  (o `spring-kafka`/`flyway-core` crus NÃO ativam a autoconfiguração nem o `@ServiceConnection`).
- Jackson 3 é o padrão (`tools.jackson.*`), com `java.time` embutido; exceções são unchecked.
- `TestRestTemplate` foi removido — usar `RestClient`/`RestTestClient`.
- `@ServiceConnection` de Kafka suporta `org.testcontainers.kafka.KafkaContainer` (imagem
  apache), não o container clássico nem o `ConfluentKafkaContainer`.
- Testcontainers não é gerenciado pelo BOM do Boot 4 — importar `testcontainers-bom`.
