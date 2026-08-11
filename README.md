# Barrier

Plataforma de KYC / PLD-FT para atender às normas regulatórias do Banco Central do Brasil.

> A barreira entre o cliente legítimo e o risco: verificação de identidade, screening e
> classificação de risco como serviço.

## Visão em uma frase

Motor de gestão de risco de clientes (modelo B2B): o cliente envia dados de um cliente
existente, a plataforma executa verificação de identidade, screening (PEP/sanções) e
classificação de risco, e devolve a decisão de forma **síncrona** (`202 em_analise`) e
por **webhook** quando a análise conclui.

## Modelo de produto

- **Fase 1 (atual):** motor de risco / decisioning. Somos **operador** de dados (LGPD);
  o cliente é o controlador e dono do cadastro.
- **Fase 2 (evolução):** plataforma completa (*system of record*) com acervo de documentos,
  biometria, retenção de 10 anos, monitoramento contínuo e reporting ao COAF.

A arquitetura da fase 1 é subconjunto da fase 2 — nada é descartado na evolução.

## Arquitetura

![Arquitetura implementada](docs/diagrams/arquitetura-atual.svg)

| Deployable | Porta | Papel |
|------------|-------|-------|
| **Risk Engine** (`services/risk-engine`) | 8080 | Intake, verificação de identidade, screening e motor de risco (módulos internos). Publica `barrier.assessment.completed`. |
| **Webhook API** (`services/webhook-api`) | 8082 | Consome o evento e entrega o resultado ao cliente com HMAC, retry e idempotência. |
| `commons` | — | Contrato de evento (`EventEnvelope`) e outbox reutilizável. |

## Stack

| Camada        | Tecnologia                          |
|---------------|-------------------------------------|
| Linguagem     | Java 25 (LTS)                       |
| Framework     | Spring Boot 4.0                     |
| Build         | Maven (monorepo Reactor)            |
| Mensageria    | Apache Kafka (coreografia + outbox) |
| Persistência  | PostgreSQL + Flyway                 |
| Estilo        | Camadas clássicas por serviço       |
| Topologia     | Monólito modular → microserviços    |

## Como rodar (dev)

Pré-requisitos: JDK 25 e Docker.

```bash
docker compose up -d          # sobe Postgres, Kafka e Kafka UI
./mvnw verify                 # build + testes (unidade + arquitetura)
./mvnw -pl services/risk-engine spring-boot:run    # sobe a Risk Engine (8080)
# webhook opcional: aponte o endpoint de destino e suba a Webhook API (8082)
WEBHOOK_TARGET_URL=https://seu-endpoint/webhook ./mvnw -pl services/webhook-api spring-boot:run
```

- Risk Engine: <http://localhost:8080/actuator/health>
  - `POST /v1/assessments` (202, aceita `Idempotency-Key`) · `GET /v1/assessments/{id}` · `POST /v1/assessments/{id}/decision` (EDD)
  - `GET /v1/subjects/{document}` · `PUT /v1/subjects/{document}/profile` (cadastro CMN 4.753) · `POST`/`GET /v1/subjects/{document}/history` (histórico interno)
  - `PUT`/`GET /v1/tenants/{tenantId}/risk-config` (override de regra por parceiro) · `PUT`/`GET /v1/risk-rules` (liga/desliga regra sem deploy)
  - toda chamada de negócio exige **`Authorization: Bearer <api-key>`**; em dev a chave é emitida
    na subida e impressa no log (`API key de DESENVOLVIMENTO emitida...`). Em produção sai por
    `POST /v1/tenants/{tenantId}/api-keys`, protegido por `X-Admin-Key`
  - endpoints administrativos (`/v1/risk-rules`, `/v1/tenants/*/risk-config`, `/v1/tenants/*/api-keys`)
    exigem **`X-Admin-Key`**
  - contrato completo e exemplos: [collection Postman](docs/api/README.md) (ainda sem OpenAPI/springdoc — Fase 5)
- Webhook API: <http://localhost:8082/actuator/health> (consome `assessment.completed` → callback assinado)
  - `PUT`/`GET`/`DELETE /v1/webhook-endpoints/{tenantId}` e `POST .../rotate-secret`, protegidos por `X-Admin-Key`
- Kafka UI: <http://localhost:8081>

## Documentação

- [Visão geral da arquitetura](docs/architecture/overview.md)
- [Bounded contexts](docs/architecture/domain-contexts.md)
- [Fluxo completo de KYC (PF e PJ)](docs/architecture/kyc-flow.md)
- [Fluxo de eventos e saga](docs/architecture/event-flow.md)
- [Requisitos regulatórios](docs/architecture/compliance.md)
- [Padrões de código e design patterns](docs/implementation/coding-standards.md)
- [Plano de implementação e progresso](docs/implementation/risk-engine-plan.md)
- [Bureau simulado de CPF (cenários de teste)](docs/implementation/bureau-simulado.md)
- [**Plano de remediação da auditoria**](docs/implementation/plano-remediacao-auditoria.md) — o que falta para produção, com critérios de pronto
- [Diagramas](docs/diagrams/README.md)
- [Registros de decisão (ADRs)](docs/adr/README.md)

## Status

🏗️ **Fluxo ponta a ponta funcionando** (build verde, 283 testes). Fases 0–8 da
Risk Engine concluídas + Webhook API. **Ainda não pode ir para produção** — o que falta é
estrutural (escala, monitoramento contínuo, criptografia em repouso) e está rastreado no
[plano de remediação](docs/implementation/plano-remediacao-auditoria.md).

- **Fase 0–1** — scaffolding, intake `202`/`GET`, transactional outbox, `Idempotency-Key`
  opcional no intake (escopo por tenant, janela de 24h, replay devolve a avaliação original).
- **Fase 2** — módulo Identity: cadeia de bureaus com prioridade e fallback — CNPJ via
  BrasilAPI e BigBoost, CPF via BigBoost ([ADR-0014](docs/adr/0014-bureau-cpf-bigboost.md),
  desligado por padrão); situação cadastral (falecido/suspensa/nula) decide antes da comparação
  de nome; [bureau simulado](docs/implementation/bureau-simulado.md) só fora de `prod` e nunca
  como fallback de bureau real; disjuntor (`CircuitBreaker`) por provider.
- **Fase 3** — módulo Screening: watchlists **ingeridas** (`ADR-0010`) — CEIS/CNEP, **PEP da
  CGU** e OFAC reais (gated por config), match por nome token a token com discriminador de CPF
  parcial, mídia negativa (provider stub) e cobertura verificável (importação vazia não
  substitui a base, health indicator e `ScreeningCoverageRiskRule` forçando revisão).
- **Fase 4** — motor de risco: cada `RiskRule` devolve um `RiskResult` padronizado (score,
  severidade, motivo, evidências, recomendação); score **0–1000** em bandas
  **LOW/MEDIUM/HIGH/CRITICAL**, com override (sanção→bloqueio, PEP→revisão), fatores
  explicáveis e **versão do motor** gravada (`risk_scores`).
- **Webhook API** — endpoint e segredo HMAC **por tenant** (rotação com janela de sobreposição),
  retry com backoff, idempotência por evento, DLT para payload ilegível e job de reconciliação.
- **Fase 6** — conformidade Bacen: `SubjectProfile` (cadastro CMN 4.753, progressivo, com gate
  de completude antes da aprovação automática — [ADR-0012](docs/adr/0012-subject-registration-profile.md))
  e `WatchlistReadinessGuard` (falha a subida em produção com watchlist incompleta —
  [ADR-0013](docs/adr/0013-watchlist-fontes-producao.md)).
- **Fase 7** — regras de risco configuráveis: override de parâmetro **por tenant**
  (`tenant_risk_config`, allowlist de regras de apetite — nunca as regulatórias fixas) e um
  **registry global de regras** (liga/desliga uma família de regra e define vigência sem
  deploy, kill switch operacional independente do override por parceiro).

- **Fase 8** — motor de risco ampliado: mídia negativa, consistência telefone×endereço
  (DDD×UF), histórico interno do subject e KYB de 1º grau (QSA da BrasilAPI). Sinais restantes
  da fila (GeoIP, reuso de device/email, VoIP, email descartável, score de crédito externo) em
  [risk-engine-plan.md](docs/implementation/risk-engine-plan.md#fase-8--motor-de-risco-ampliado-fila-de-prs-em-andamento).
- **Segurança/integridade** — autenticação por API key com tenant derivado da credencial
  (`X-Client-Id` não é mais lido), gate de admin, PII mascarada em log, reivindicação exclusiva
  por lease no processamento, transação por avaliação e estado de falha explícito.

**Próximo (por onda, no [plano de remediação](docs/implementation/plano-remediacao-auditoria.md)):**
Onda 1 fecha a integridade da decisão; Onda 2 é compliance de verdade (COAF/SISCOAF, retenção de
10 anos, criptografia em repouso, UBO além do 1º grau, rescreening periódico); Onda 3 é escala e
antifraude — incluindo o monitoramento transacional contínuo pós-onboarding (PIX em rajada,
layering), a maior mudança estrutural da fila, já que o motor hoje só roda no onboarding.
Hardening pendente da Fase 5: OpenAPI/springdoc.
