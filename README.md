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

- Risk Engine: <http://localhost:8080/actuator/health> · `POST /v1/assessments` (202) · `GET /v1/assessments/{id}`
- Webhook API: <http://localhost:8082/actuator/health> (consome `assessment.completed` → callback assinado)
- Kafka UI: <http://localhost:8081>

## Documentação

- [Visão geral da arquitetura](docs/architecture/overview.md)
- [Bounded contexts](docs/architecture/domain-contexts.md)
- [Fluxo de eventos e saga](docs/architecture/event-flow.md)
- [Requisitos regulatórios](docs/architecture/compliance.md)
- [Registros de decisão (ADRs)](docs/adr/README.md)

## Status

🏗️ **Fase 4 concluída** — motor de **Risk scoring**. Cada `RiskRule` (Strategy) devolve um
`RiskResult` padronizado (score, severidade, motivo, evidências, recomendação); o
`RiskScoringService` soma numa escala **0–1000** com bandas **BAIXO/MEDIO/ALTO/CRITICO** e
toma a recomendação mais severa (aprovar/revisar/bloquear), gravando os fatores e a **versão
do motor** (`risk_scores`). Regras iniciais: **sanção = bloqueio**, **PEP = revisão (EDD)**,
**identidade não confirmada**, e o esqueleto de **estrutura societária (PJ)**. A recomendação
do motor vira o status da avaliação; os fatores explicáveis voltam no `GET`.

Além da Risk Engine, existe a **Webhook API** (`services/webhook-api`): consome
`barrier.assessment.completed` do Kafka e entrega o resultado no endpoint do cliente, com
**assinatura HMAC**, **retry com backoff**, idempotência por evento e rastreio em
`deliveries` (schema Postgres próprio `webhook`). Próximo: Fase 5 (hardening: OpenAPI,
idempotência no intake, mascaramento). Ver o [plano de implementação](docs/implementation/risk-engine-plan.md).
