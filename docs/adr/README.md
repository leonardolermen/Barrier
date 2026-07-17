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
| [0007](0007-java25-spring-boot.md) | Java 25 + Spring Boot 3 (atualizado p/ 4.0 em prática) | Aceito |
| [0008](0008-monorepo-maven.md) | Monorepo Maven multi-módulo           | Aceito    |
| [0009](0009-risk-engine-modular-monolith-first.md) | Risk Engine como monólito modular primeiro | Aceito |
| [0010](0010-watchlists-ingeridas.md) | Watchlists ingeridas (arquivo → tabela), não por request | Aceito |
| [0011](0011-subject-compartilhado-acesso-por-associacao.md) | Subject compartilhado (1 por documento) com acesso por associação | Aceito |
| [0012](0012-subject-registration-profile.md) | Cadastro do subject (CMN 4.753) como agregado próprio, 1:1 | Aceito |
| [0013](0013-watchlist-fontes-producao.md) | Watchlist em produção: fail-fast sem CGU/OFAC habilitados | Aceito |
| [0014](0014-bureau-cpf-bigboost.md) | Bureau real de CPF via BigBoost (self-service, sem CNPJ) | Aceito |

## Status possíveis

- **Proposto** — em discussão, ainda não firmado.
- **Aceito** — decisão vigente.
- **Substituído** — trocado por outro ADR (com link).
- **Depreciado** — não vale mais, sem substituto.
