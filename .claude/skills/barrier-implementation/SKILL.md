---
name: barrier-implementation
description: >
  Standards and step-by-step checklist for writing or reviewing implementation code in the
  Barrier KYC/PLD-FT platform (Java 25 + Spring Boot 3, classic layered architecture,
  transactional outbox, Kafka, Testcontainers/ArchUnit). Use whenever creating or modifying
  a module or service in the barrier repo — controllers, services, repositories, external
  clients, events, migrations, or tests — so every change follows the agreed patterns.
---

# Barrier — implementation standards

Aplique isto SEMPRE que escrever ou revisar código de implementação no repositório Barrier.

## Antes de escrever código

1. Leia os padrões completos em [docs/implementation/coding-standards.md](../../../docs/implementation/coding-standards.md).
2. Se estiver na Risk Engine, siga o plano faseado em [docs/implementation/risk-engine-plan.md](../../../docs/implementation/risk-engine-plan.md).
3. Confirme em qual fase/PR a mudança se encaixa; não misture fases num PR.

## Regras de camada (não violar — há ArchUnit)

- `controller → service → repository`; controller nunca toca repository direto.
- `service` acessa integração externa **só por interface** do pacote `client`.
- DTO no controller, domínio no service, entidade no repository. Converter com MapStruct.
- Entidade JPA não vaza para o controller. Sem regra de negócio em controller/repository.

## Checklist por tipo de mudança

**Novo endpoint:** DTO validado (Bean Validation) → service → mapper → status HTTP correto
(`202` para intake) → documentar no OpenAPI → teste de integração (Testcontainers).

**Publicação de evento:** NUNCA publicar direto no Kafka. Gravar em `outbox` na mesma
`@Transactional` do estado. Envelope com `eventId`, `assessmentId`, `occurredAt`, `version`.
Reusar o componente de outbox do `commons`.

**Consumo de evento:** idempotente (descartar `eventId` já visto); assumir at-least-once.

**Integração externa (bureau/watchlist):** interface no `client` + impl; começar com stub;
indisponibilidade não derruba a avaliação (registrar resultado indisponível).

**Regra de risco/screening:** implementar como Strategy (`RiskRule`/match rule); fatores
devem ser explicáveis e retornados na resposta (exigência regulatória).

**Mudança de schema:** nova migration Flyway `V00X__...sql`; nunca editar migration aplicada.

## Design patterns esperados

Layered · Repository · Transactional Outbox · Gateway/Adapter (integrações) · Strategy
(regras) · Pipeline/Orchestrator (AssessmentService) · Value Object (`Cpf`/`Cnpj`) ·
DTO+Mapper · Idempotency key. Usar só quando resolvem problema real — sem abstração
especulativa.

## Testes obrigatórios

- Unitário (regra de negócio, VOs) sem Spring; Mockito + AssertJ.
- Integração com Testcontainers (Postgres + Kafka) para fluxo e outbox.
- ArchUnit para as regras de camada.
- Todo bug corrigido nasce com teste que o reproduz.

## Segurança/LGPD

- Nunca logar CPF/CNPJ sem mascarar. Segredos por env, nunca no código.
- Guardar o mínimo (somos operador nesta fase).

## Definição de pronto

Compila sem warning · Spotless aplicado · testes (unidade+integração+arquitetura) verdes ·
OpenAPI atualizado se o contrato mudou · commit `tipo(escopo): descrição`.
