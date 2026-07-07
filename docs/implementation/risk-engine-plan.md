# Plano de implementação — Risk Engine API

Plano faseado para construir a **Risk Engine API** (ver
[ADR-0009](../adr/0009-risk-engine-modular-monolith-first.md)) seguindo os
[padrões de código](coding-standards.md). Cada fase é entregável, testável e mergeável
sozinha.

## Progresso

| Fase | Escopo | Estado |
|------|--------|--------|
| 0 | Scaffolding (monorepo, Spring Boot 4, Flyway, ArchUnit) | ✅ |
| 1 | Intake `202` + `GET` + transactional outbox | ✅ |
| 2 | Módulo Identity (BureauProvider, `identity_checks`) | ✅ |
| 3 | Módulo Screening (WatchlistProvider + regras, `screening_results`) | ✅ |
| 4 | Motor de risco (RiskRule → RiskResult, score 0–1000, `risk_scores`) | ✅ |
| — | **Webhook API** (deployable separado: HMAC, retry, idempotência) | ✅ |
| 5 | Hardening (OpenAPI, idempotency-key no intake, mascaramento) | ⏳ |

Detalhe do que ficou diferente do plano original: o motor de risco (Fase 4) adotou o contrato
padronizado `RiskResult` (score/severidade/motivo/evidências/recomendação), escala **0–1000**
com nível **CRITICAL** adicional e **versionamento do motor** (`engine_version`). Estado atual
completo em [CLAUDE.md](../../CLAUDE.md).

## Estrutura do repositório (monorepo Maven — ADR-0008)

```
barrier/
├── pom.xml                          POM pai: versões, plugins, Java 25, Spotless
├── docker-compose.yml               Postgres + Kafka + Kafka UI
├── commons/
│   ├── pom.xml
│   └── src/main/java/com/barrier/commons/
│       ├── event/                   envelope de evento, correlação
│       └── outbox/                  entidade Outbox + relay + publisher (reutilizável)
└── services/
    └── risk-engine/
        ├── pom.xml
        └── src/main/java/com/barrier/riskengine/
            ├── assessment/          orquestra, REST, agrega decisão
            ├── identity/            valida CPF/CNPJ · client BureauProvider
            ├── screening/           match PEP/sanções · client WatchlistProvider
            ├── risk/                score baixo/médio/alto (Strategy)
            └── config/              Kafka, security, OpenAPI, beans
```

Cada módulo interno segue `controller/service/repository/client/domain/dto`.

## Contrato externo (o que o cliente vê)

```
POST /v1/assessments
  body: { document, documentType, name, ... }
  201/202 → { id, status: "EM_ANALISE" }

GET /v1/assessments/{id}
  200 → { id, status, riskLevel?, decision?, factors[], completedAt? }
  404 → problem+json
```

Evento emitido ao concluir: `barrier.assessment.completed` (via outbox).

## Modelo de dados (schema risk_engine)

| Tabela              | Papel                                                        |
|---------------------|--------------------------------------------------------------|
| `assessments`       | agregado principal: id, documento, status, risk_level, decisão, timestamps |
| `identity_checks`   | resultado da verificação de identidade por assessment        |
| `screening_results` | hits de PEP/sanções por assessment                           |
| `risk_scores`       | score e fatores por assessment                               |
| `outbox`            | eventos pendentes de publicação (id, tipo, payload, status, tentativas) |

---

## Fase 0 — Scaffolding

**Objetivo:** projeto compila, sobe e responde health check.

- POM pai (`pom.xml`) com `dependencyManagement` (Spring Boot BOM, Java 25, Spotless).
- Módulos Maven `commons` e `services/risk-engine` no `<modules>` do pai.
- `docker-compose.yml`: Postgres, Kafka, Kafka UI.
- `application.yml` com profiles (`local`, `test`).
- Flyway ligado; migration `V001__baseline.sql` vazia/estrutural.
- ArchUnit configurado com as regras de camada (mesmo que os pacotes estejam vazios).

**Aceite:** `./mvnw verify` verde; app sobe; `GET /actuator/health` = UP.

---

## Fase 1 — Intake e ciclo de vida do assessment

**Objetivo:** o fluxo `POST → 202 → GET status` funciona, com identity/screening/risk
ainda como stubs, e o evento de conclusão sai no Kafka via outbox.

- **Value objects:** `Cpf`, `Cnpj`, `AssessmentId` (records validados).
- **Domínio:** agregado `Assessment` com `AssessmentStatus`; métodos de transição.
- **Persistência:** entidade + `AssessmentRepository` (Spring Data) + migration.
- **Outbox no commons:** entidade `OutboxEvent`, `OutboxRepository`, `OutboxRelay`
  (`@Scheduled`), `EventPublisher`.
- **Service:** `AssessmentService` orquestra (por ora chama stubs) e grava outbox na mesma tx.
- **Controller:** `POST /v1/assessments` (202) e `GET /v1/assessments/{id}`.
- **Erro:** `@RestControllerAdvice` + problem+json; `AssessmentNotFoundException`.
- **Correlação:** filtro que põe `assessmentId` no MDC.

**Patterns:** Outbox, Repository, Value Object, DTO+Mapper, Factory (evento).

**Aceite:** POST cria e responde 202; GET reflete status; `assessment.completed` aparece no
tópico após conclusão (stub decide APROVADO); teste de integração com Testcontainers cobre
o caminho feliz; ArchUnit verde.

---

## Fase 2 — Módulo Identity

**Objetivo:** validar o documento de verdade, com bureau atrás de interface.

- Interface `BureauProvider` (pacote `client`) + `StubBureauProvider` (retorna válido) e
  esqueleto de `SerproBureauProvider` (não integra ainda).
- `IdentityService` chama o provider; grava `identity_checks`.
- Tratamento de indisponibilidade do bureau (não derruba a avaliação).

**Patterns:** Gateway/Adapter, Strategy (seleção de provider por tipo de documento).

**Aceite:** identidade inválida → assessment REPROVADO; bureau indisponível → resultado
"indisponível" registrado; testes unitários do service com provider mockado.

---

## Fase 3 — Módulo Screening

**Objetivo:** match contra listas (PEP/sanções), com watchlist atrás de interface.

- Interface `WatchlistProvider` + `StubWatchlistProvider` (lista em memória para dev).
- `ScreeningService` calcula hits; grava `screening_results`.
- Regras de match como **Strategy** (`PepMatchRule`, `SanctionMatchRule`).

**Patterns:** Gateway/Adapter, Strategy, Chain (aplicar regras em sequência).

**Aceite:** documento em lista → hit registrado; sem hit → limpo; testes por regra.

---

## Fase 4 — Módulo Risk scoring

**Objetivo:** transformar identidade + screening em `RiskLevel` + fatores explicáveis.

- `RiskRule` (Strategy): cada regra recebe o contexto e contribui com peso/fator.
- `RiskScoringService` agrega as regras → `RiskLevel` (LOW/MEDIUM/HIGH/CRITICAL) + fatores.
- Decisão: LOW/MEDIUM → APROVADO; HIGH → EM_REVISAO; CRITICAL ou override → REPROVADO
  (case management é fase 2, aqui só marca o estado).
- Grava `risk_scores`; fatores retornados no `GET`.

**Patterns:** Strategy, Composite (soma de regras), Factory (resultado).

**Aceite:** cenários baixo/médio/alto cobertos; fatores explicáveis presentes na resposta
(exigência regulatória de explicabilidade).

---

## Fase 5 — Hardening

**Objetivo:** deixar pronto para uso real.

- Idempotência de publicação/consumo; retry/backoff do relay de outbox.
- OpenAPI completo (springdoc) e exemplos.
- Logs estruturados + mascaramento de CPF/CNPJ.
- Suite de arquitetura (ArchUnit) cobrindo todas as regras de camada.
- Testcontainers para Postgres **e** Kafka no CI.
- Expurgo da tabela outbox após confirmação.

**Aceite:** CI verde com testes de unidade, integração e arquitetura; sem dado sensível em
log; contrato publicado.

---

## Fora de escopo (fases seguintes / outros deployables)

- **Webhook API** — consome `assessment.completed` e faz callbacks (próximo deployable).
- **Case management** e **Audit** — fase 2.
- Integração real com Serpro/Serasa e watchlists oficiais — substituir stubs por impls.

## Ordem sugerida de PRs

`Fase 0 → 1 → 2 → 3 → 4 → 5`, um PR por fase (ou por sub-entrega dentro da fase, se ficar
grande). Cada PR verde no CI antes do próximo.
