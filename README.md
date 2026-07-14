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

- Risk Engine: <http://localhost:8080/actuator/health> · `POST /v1/assessments` (202) · `GET /v1/assessments/{id}` · `PUT /v1/subjects/{document}/profile` (cadastro CMN 4.753)
  - toda chamada exige o header **`X-Client-Id`** (tenant); em dev use `X-Client-Id: default`
- Webhook API: <http://localhost:8082/actuator/health> (consome `assessment.completed` → callback assinado)
- Kafka UI: <http://localhost:8081>

## Documentação

- [Visão geral da arquitetura](docs/architecture/overview.md)
- [Bounded contexts](docs/architecture/domain-contexts.md)
- [Fluxo de eventos e saga](docs/architecture/event-flow.md)
- [Requisitos regulatórios](docs/architecture/compliance.md)
- [Padrões de código e design patterns](docs/implementation/coding-standards.md)
- [Plano de implementação e progresso](docs/implementation/risk-engine-plan.md)
- [Diagramas](docs/diagrams/README.md)
- [Registros de decisão (ADRs)](docs/adr/README.md)

## Status

🏗️ **Fluxo ponta a ponta funcionando** (build verde, 45 testes). Fases 0–4 da Risk Engine
concluídas + Webhook API:

- **Fase 0–1** — scaffolding, intake `202`/`GET`, transactional outbox.
- **Fase 2** — módulo Identity (`BureauProvider` atrás de interface, `identity_checks`).
- **Fase 3** — módulo Screening (`WatchlistProvider` + regras Strategy, `screening_results`).
- **Fase 4** — motor de risco: cada `RiskRule` devolve um `RiskResult` padronizado (score,
  severidade, motivo, evidências, recomendação); score **0–1000** em bandas
  **LOW/MEDIUM/HIGH/CRITICAL**, com override (sanção→bloqueio, PEP→revisão), fatores
  explicáveis e **versão do motor** gravada (`risk_scores`).
- **Webhook API** — entrega assinada (HMAC), retry com backoff, idempotência por evento.
- **Fase 6** — conformidade Bacen: `SubjectProfile` (cadastro CMN 4.753, progressivo, com gate
  de completude antes da aprovação automática — [ADR-0012](docs/adr/0012-subject-registration-profile.md))
  e `WatchlistReadinessGuard` (falha a subida em produção com watchlist incompleta —
  [ADR-0013](docs/adr/0013-watchlist-fontes-producao.md)).

**Próximo:** Fase 5 (hardening: OpenAPI, idempotency-key no intake, mascaramento de CPF/CNPJ) e
o backlog de compliance que ainda falta (COAF/SISCOAF, retenção de 10 anos, criptografia em
repouso, UBO além do 1º grau, bureau real de CPF) — listado em detalhe na
[Fase 6 do plano de implementação](docs/implementation/risk-engine-plan.md#fase-6--conformidade-bacen-cadastro-e-screening-pronto-para-produção).
