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

Java 25 · Spring Boot 3 · Maven (monorepo) · PostgreSQL + Flyway · Kafka · MapStruct ·
JUnit 5 / Testcontainers / ArchUnit. Pacote raiz `com.barrier.<contexto>`.

## Estado atual

Fase de arquitetura concluída. Próximo passo: implementar a Risk Engine seguindo o plano
faseado (Fase 0 → 5). Código ainda não iniciado.
