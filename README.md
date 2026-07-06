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
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- Kafka UI: <http://localhost:8081>

## Documentação

- [Visão geral da arquitetura](docs/architecture/overview.md)
- [Bounded contexts](docs/architecture/domain-contexts.md)
- [Fluxo de eventos e saga](docs/architecture/event-flow.md)
- [Requisitos regulatórios](docs/architecture/compliance.md)
- [Registros de decisão (ADRs)](docs/adr/README.md)

## Status

🏗️ **Fase 0 (scaffolding) concluída** — monorepo Maven, módulos `commons` e
`services/risk-engine`, Spring Boot 4 + Java 25, Flyway, Kafka e ArchUnit configurados;
app sobe e responde health. Próximo: Fase 1 (intake `202` + outbox). Ver o
[plano de implementação](docs/implementation/risk-engine-plan.md).
