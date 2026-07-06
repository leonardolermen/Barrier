# Architecture Decision Records (ADRs)

Registros das decisões de arquitetura significativas, com contexto e consequências.
Formato baseado em [Michael Nygard](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

## Índice

| ADR | Título                                         | Status    |
|-----|------------------------------------------------|-----------|
| [0001](0001-microservices-topology.md) | Topologia de microserviços        | Substituído |
| [0002](0002-classic-layered-architecture.md) | Camadas clássicas por serviço | Aceito    |
| [0003](0003-event-driven-kafka-choreography.md) | Event-driven com Kafka (coreografia) | Aceito |
| [0004](0004-outbox-pattern.md) | Outbox pattern para publicação de eventos | Aceito |
| [0005](0005-product-model-risk-engine.md) | Modelo de produto: motor de risco (A→B) | Aceito |
| [0006](0006-sync-and-webhook-response.md) | Retorno síncrono + webhook       | Aceito    |
| [0007](0007-java25-spring-boot.md) | Java 25 + Spring Boot 3           | Aceito    |
| [0008](0008-monorepo-gradle.md) | Monorepo Gradle multi-módulo          | Proposto  |
| [0009](0009-risk-engine-modular-monolith-first.md) | Risk Engine como monólito modular primeiro | Aceito |

## Status possíveis

- **Proposto** — em discussão, ainda não firmado.
- **Aceito** — decisão vigente.
- **Substituído** — trocado por outro ADR (com link).
- **Depreciado** — não vale mais, sem substituto.
