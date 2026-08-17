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
| 6 | Conformidade Bacen — cadastro (CMN 4.753) e screening pronto para produção | ✅ |
| 7 | Regras de risco configuráveis por tenant (`tenant_risk_config`, API de gestão) | ✅ |

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

## Fase 6 — Conformidade Bacen: cadastro e screening pronto para produção

**Objetivo:** fechar os dois gaps de conformidade mais diretos identificados na análise de
arquitetura: dados de cadastro exigidos pela CMN 4.753 (hoje só documento/nome/tipo eram
guardados) e o risco de subir em produção com a watchlist só no CSV seed.

- **`SubjectProfile`** (novo agregado 1:1 com `Subject`, ver
  [ADR-0012](../adr/0012-subject-registration-profile.md)): cadastro progressivo via
  `PUT /v1/subjects/{document}/profile`; dados objetivos de PJ do bureau (`CompanyProfile`)
  passam a ser persistidos em vez de descartados; `RegistrationCompleteness` é o checklist
  mínimo por tipo de documento (PF/PJ).
- **Gate de completude:** `AssessmentProcessor` rebaixa a recomendação de `APROVADO` para
  `EM_REVISAO` quando o cadastro está incompleto, reaproveitando o workflow humano existente.
- **`WatchlistReadinessGuard`** (ver
  [ADR-0013](../adr/0013-watchlist-fontes-producao.md)): falha a subida se o profile `prod`
  estiver ativo e só a watchlist `SEED` estiver disponível; `application-prod.yml` habilita
  CGU/OFAC por padrão nesse profile.
- **`BigBoostBureauProvider`** (ver [ADR-0014](../adr/0014-bureau-cpf-bigboost.md)): bureau real
  de CPF (dataset `basic_data` da BigBoost/BigDataCorp), desligado por padrão
  (`barrier.identity.bigboost.enabled=false`) — dev/testes continuam no `StubBureauProvider`;
  habilitar é só configuração (flag + `AccessToken`/`TokenId`), sem CNPJ necessário para
  contratar (ao contrário do Serpro).

**Aceite:** avaliação de PF/PJ com cadastro incompleto cai em `EM_REVISAO` com fator explicando
os campos faltantes; dados de PJ do bureau aparecem em `subject_profiles` depois da verificação
de identidade; subida com `SPRING_PROFILES_ACTIVE=prod` e watchlist não habilitada falha no
startup.

### Backlog identificado (próxima rodada, ainda não implementado)

- **COAF/SISCOAF** (Lei 9.613/98) — comunicação automática de operações suspeitas.
- **Retenção de 10 anos** (Circular BCB 3.978) — política de retenção/expurgo, hoje inexistente.
- **Criptografia em repouso / KMS** — documento e cadastro hoje ficam em texto plano no banco.
- **UBO além do 1º grau** — `CorporateStructureRiskRule` só navega o QSA direto; falta
  navegação da árvore societária até a pessoa física final. Restrições de custo obrigatórias
  antes de escrever o código: [ADR-0018](../adr/0018-custo-de-navegacao-ubo.md).
- **Bureau real de CPF via Serpro** — `BigBoostBureauProvider` (ADR-0014) já cobre esse gap sem
  depender de CNPJ; `SerproBureauProvider` continua como esqueleto para quando a empresa estiver
  formalizada e o convênio oficial (Receita Federal) fizer sentido como alternativa/fallback.
- **Idempotency-key no intake** — um `POST /v1/assessments` duplicado ainda cria uma segunda
  avaliação (o `Subject` fica deduplicado, o `Assessment` não).

## Fase 8 — Motor de risco ampliado (fila de PRs em andamento)

**Objetivo:** cobrir as dimensões de risco que faltam hoje (KYC além do bureau, mídia negativa,
sinais de fraude digital, histórico/score externo, monitoramento contínuo), mantendo cada
dimensão como módulo independente (interface de provider + `RiskRule` própria), sem virar
caixa-preta — toda regra nova segue o mesmo contrato `RiskResult` (score/severidade/motivo/
evidências/recomendação) já em produção desde a Fase 4.

Ordem (cada item é um PR próprio, com testes unitário + integração; próximo só começa com o
anterior mergeado):

1. **Rule engine — criticidade/enabled/vigência.** Pré-requisito estrutural pros itens
   seguintes: cada `RiskRule` ganha metadado consultável/alterável sem deploy (criticidade
   INFO/ALERT/REVIEW/BLOCK, habilitada ou não, vigência), reaproveitando
   `tenant_risk_config`/`TenantRiskConfigService` (Fase 7). `RiskScoringService` pula regra
   desabilitada ou fora de vigência.
2. **Mídia negativa** — `NegativeMediaProvider` (Strategy/Gateway) + `NegativeMediaRiskRule`;
   termos: lavagem de dinheiro, corrupção, fraude, tráfico, terrorismo, pirâmide financeira.
   Stub em dev; interface pronta para BigBoost/LexisNexis/Dow Jones.
3. **Consistência cadastral** — `ConsistencyRiskRule`: nome divergente do bureau, CPF de titular
   diferente do informado, DDD do telefone incompatível com o estado do endereço. Usa dados já
   existentes (`SubjectProfile`/`CompanyProfile`/`IdentityCheck`), sem provider novo.
4. **Sinais de rede** — módulo `device`: intake aceita `ip`/`deviceId`/`fingerprint` opcionais;
   `GeoIpProvider` (Strategy) + `DeviceRiskRule` (GeoIP divergente do endereço, VPN/proxy, mesmo
   device em N cadastros recentes — nova tabela `device_seen`). Maior escopo; dividir em
   sub-PRs se necessário.
5. **Telefone e email** — `PhoneRiskRule` (VoIP, descartável, DDD/operadora incompatível) e
   `EmailRiskRule` (domínio descartável/temporário, idade do domínio, reuso do mesmo email em
   vários cadastros). Providers atrás de interface, stub em dev.
6. **Histórico interno e score externo** — tabela `subject_history` (chargeback, PIX devolvido,
   denúncia, conta encerrada por fraude) + `HistoryRiskRule`; `CreditScoreProvider` (Strategy)
   para Serasa/Boa Vista/SCR, stub em dev.
7. **Monitoramento transacional contínuo (PLD/FT pós-onboarding)** — maior mudança estrutural:
   hoje o motor só roda no onboarding. Precisa de um novo fluxo consumindo eventos de transação
   via Kafka; **aqui sim vale reavaliar um serviço separado** (ciclo de vida e escala diferentes
   do onboarding — ao contrário da decisão tomada para cadastro/config, que ficaram no
   monólito). Regras: PIX em rajada, valor alto em conta nova, layering/smurfing/circularização,
   contas de passagem. Exige ADR novo se o split for adiante.

**Aceite por item:** regra nova cobre cenário positivo/negativo em teste unitário; teste de
integração (Testcontainers) prova que a regra entra no score/fatores explicáveis de uma
avaliação real; ArchUnit e `./mvnw test` verdes antes de abrir o PR seguinte.

## Fora de escopo (fases seguintes / outros deployables)

- **Webhook API** — consome `assessment.completed` e faz callbacks (próximo deployable).
- **Case management** e **Audit** — fase 2.
- Integração real com Serpro/Serasa e watchlists oficiais — substituir stubs por impls.

## Ordem sugerida de PRs

`Fase 0 → 1 → 2 → 3 → 4 → 5`, um PR por fase (ou por sub-entrega dentro da fase, se ficar
grande). Cada PR verde no CI antes do próximo.
