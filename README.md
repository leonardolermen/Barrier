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
  - `POST /v1/assessments` (202) · `GET /v1/assessments/{id}` · `POST /v1/assessments/{id}/decision` (EDD)
  - `GET /v1/subjects/{document}` · `PUT /v1/subjects/{document}/profile` (cadastro CMN 4.753) · `POST`/`GET /v1/subjects/{document}/history` (histórico interno)
  - `PUT`/`GET /v1/tenants/{tenantId}/risk-config` (override de regra por parceiro) · `PUT`/`GET /v1/risk-rules` (liga/desliga regra sem deploy)
  - toda chamada exige o header **`X-Client-Id`** (tenant); em dev use `X-Client-Id: default`
  - contrato completo e exemplos: [collection Postman](docs/api/README.md) (ainda sem OpenAPI/springdoc — Fase 5)
- Webhook API: <http://localhost:8082/actuator/health> (consome `assessment.completed` → callback assinado)
- Kafka UI: <http://localhost:8081>

## Documentação

- [Visão geral da arquitetura](docs/architecture/overview.md)
- [Bounded contexts](docs/architecture/domain-contexts.md)
- [Fluxo completo de KYC (PF e PJ)](docs/architecture/kyc-flow.md)
- [Fluxo de eventos e saga](docs/architecture/event-flow.md)
- [Requisitos regulatórios](docs/architecture/compliance.md)
- [Padrões de código e design patterns](docs/implementation/coding-standards.md)
- [Plano de implementação e progresso](docs/implementation/risk-engine-plan.md)
- [Diagramas](docs/diagrams/README.md)
- [Registros de decisão (ADRs)](docs/adr/README.md)

## Status

🏗️ **Fluxo ponta a ponta funcionando** (build verde, 144 testes em `main`). Fases 0–7 da
Risk Engine concluídas + Webhook API:

- **Fase 0–1** — scaffolding, intake `202`/`GET`, transactional outbox.
- **Fase 2** — módulo Identity: cadeia de bureaus com fallback — CNPJ real (BrasilAPI), CPF
  real (BigBoost, `ADR-0014`, desligado por padrão), stub como último fallback.
- **Fase 3** — módulo Screening: watchlists **ingeridas** (`ADR-0010`) — CGU/CEIS/CNEP e OFAC
  reais (gated por config), match fuzzy por nome, mais mídia negativa (stub).
- **Fase 4** — motor de risco: cada `RiskRule` devolve um `RiskResult` padronizado (score,
  severidade, motivo, evidências, recomendação); score **0–1000** em bandas
  **LOW/MEDIUM/HIGH/CRITICAL**, com override (sanção→bloqueio, PEP→revisão), fatores
  explicáveis e **versão do motor** gravada (`risk_scores`).
- **Webhook API** — entrega assinada (HMAC), retry com backoff, idempotência por evento.
- **Fase 6** — conformidade Bacen: `SubjectProfile` (cadastro CMN 4.753, progressivo, com gate
  de completude antes da aprovação automática — [ADR-0012](docs/adr/0012-subject-registration-profile.md))
  e `WatchlistReadinessGuard` (falha a subida em produção com watchlist incompleta —
  [ADR-0013](docs/adr/0013-watchlist-fontes-producao.md)).
- **Fase 7** — regras de risco configuráveis: override de parâmetro **por tenant**
  (`tenant_risk_config`, allowlist de regras de apetite — nunca as regulatórias fixas) e um
  **registry global de regras** (liga/desliga uma família de regra e define vigência sem
  deploy, kill switch operacional independente do override por parceiro).

**Em revisão (branches empilhadas, ainda não mergeadas em `main`):** Fase 8 — motor de risco
ampliado com mais sinais explicáveis: consistência telefone×endereço, GeoIP, reuso de
device/email, VoIP, email descartável, histórico interno (chargeback/PIX devolvido/fraude) e
gancho pronto para score de crédito externo (Serasa/Boa Vista/SCR). Detalhe completo e ordem
das PRs em [risk-engine-plan.md](docs/implementation/risk-engine-plan.md#fase-8--motor-de-risco-ampliado-fila-de-prs-em-andamento).

**Próximo:** Fase 5 (hardening: OpenAPI, idempotency-key no intake, mascaramento de CPF/CNPJ),
monitoramento transacional contínuo pós-onboarding (PIX em rajada, layering — maior mudança
estrutural da fila, motor hoje só roda no onboarding) e o backlog de compliance que ainda
falta (COAF/SISCOAF, retenção de 10 anos, criptografia em repouso, UBO além do 1º grau) —
listado em detalhe no [plano de implementação](docs/implementation/risk-engine-plan.md).
