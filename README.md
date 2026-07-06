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
./mvnw -pl services/risk-engine spring-boot:run   # sobe a Risk Engine
```

- Health: <http://localhost:8080/actuator/health>
- Kafka UI: <http://localhost:8081>
- API: `POST /v1/assessments` (202) · `GET /v1/assessments/{id}` (Swagger vem na Fase 5)

## Documentação

- [Visão geral da arquitetura](docs/architecture/overview.md)
- [Bounded contexts](docs/architecture/domain-contexts.md)
- [Fluxo de eventos e saga](docs/architecture/event-flow.md)
- [Requisitos regulatórios](docs/architecture/compliance.md)
- [Registros de decisão (ADRs)](docs/adr/README.md)

## Status

🏗️ **Fase 2 concluída** — sobre a Fase 1 (intake `202` + outbox), agora com o módulo
**Identity**: `BureauProvider` (integração atrás de interface, stub ativo + esqueleto Serpro),
`IdentityService` seleciona o provider por tipo de documento (Strategy) e grava
`identity_checks`. O `AssessmentProcessor` decide pela identidade: NOT_FOUND/MISMATCH →
REPROVADO; verificada ou bureau indisponível → APROVADO. Próximo: Fase 3 (Screening). Ver o
[plano de implementação](docs/implementation/risk-engine-plan.md).
