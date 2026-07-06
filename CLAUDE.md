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

Fase 1 concluída: intake (`POST /v1/assessments` 202, `GET /v1/assessments/{id}`), agregado
`Assessment` + VOs (Cpf/Cnpj), processamento assíncrono stub e transactional outbox
publicando `barrier.assessment.completed`. Próximo: Fase 2 (módulo Identity com bureau
atrás de interface), seguindo o plano faseado (Fase 2 → 5).

Build validado: `./mvnw test` verde (18 testes, inclui integração com Testcontainers).
JDK local: `C:\Users\leona\.jdks\corretto-25.0.3` (setar `JAVA_HOME` antes do `mvnw`).

Peculiaridades do Spring Boot 4 (aprendidas na prática):
- Autoconfig é modularizada: use `spring-boot-starter-kafka` e `spring-boot-starter-flyway`
  (o `spring-kafka`/`flyway-core` crus NÃO ativam a autoconfiguração nem o `@ServiceConnection`).
- Jackson 3 é o padrão (`tools.jackson.*`), com `java.time` embutido; exceções são unchecked.
- `TestRestTemplate` foi removido — usar `RestClient`/`RestTestClient`.
- `@ServiceConnection` de Kafka suporta `org.testcontainers.kafka.KafkaContainer` (imagem
  apache), não o container clássico nem o `ConfluentKafkaContainer`.
- Testcontainers não é gerenciado pelo BOM do Boot 4 — importar `testcontainers-bom`.
