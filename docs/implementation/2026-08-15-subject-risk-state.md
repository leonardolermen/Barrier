# Projeção de risco corrente + webhook de mudança de nível — plano de implementação

> **Para quem executa:** passos com checkbox são a unidade de trabalho. TDD: teste que falha,
> rodar e ver falhar, implementação mínima, rodar e ver passar, commit.

**Goal:** dar ao Barrier um "risco corrente do cliente" consultável e avisar o parceiro por
webhook quando esse risco muda de nível.

**Arquitetura:** `risk_scores` é snapshot imutável por avaliação e continua sendo. Ao lado
dele entra uma **projeção viva** `subject_risk_state`, chaveada por `(subject_id, tenant_id)`,
atualizada na mesma transação em que a avaliação conclui. A mudança de `risk_level` entre o
estado anterior e o novo é o gatilho do evento `barrier.subject.risk_level_changed`, publicado
pela outbox e entregue pela webhook-api com a infraestrutura de endpoint/HMAC que já existe.

**Spec:** [fila-origem.md](fila-origem.md) F3 e F4 · [licoes-do-origem.md](licoes-do-origem.md)
prioridade 2

**Stack:** Java 25 · Spring Boot 4 · Postgres/Flyway · Kafka · JUnit 5 · Testcontainers ·
ArchUnit

## Restrições globais

- Camadas `controller → service → repository`; ArchUnit valida.
- Evento **só** por outbox.
- Migration imutável; **próxima livre: V041**.
- Documento nunca aparece sem máscara em log ou payload.
- Nenhuma mudança de regra de risco aqui — `ENGINE_VERSION` **não** sobe.
- `./mvnw test` verde ao fim de cada tarefa (`JAVA_HOME=C:\Users\leona\.jdks\corretto-25.0.3`).

## Estrutura de arquivos

| Arquivo | Responsabilidade |
|---|---|
| `V041__subject_risk_state.sql` | tabela da projeção + backfill do que já existe |
| `subject/state/domain/SubjectRiskState.java` | o estado corrente como valor |
| `subject/state/domain/RiskLevelTransition.java` | de → para, quando houve mudança |
| `subject/state/repository/…` | entity + impl + interfaces (padrão do `subject.profile`) |
| `subject/state/service/SubjectRiskStateService.java` | upsert monotônico e leitura |
| `subject/state/controller/SubjectRiskStateController.java` | `GET .../risk-state` |
| `assessment/service/AssessmentProcessor.java` | grava a projeção ao concluir |
| `assessment/service/AssessmentService.java` | grava a projeção na decisão manual |
| `subject/state/service/RiskLevelChangeEventPublisher.java` | evento na outbox |
| `webhook-api: SubjectRiskLevelListener` | consumo e entrega |

**Correção durante a execução (2026-08-15):** a projeção **não** ficou em `subject.state`. O
ArchUnit (`sem_ciclos_entre_modulos`) rejeitou: `assessment → subject.state → assessment` (o
service recebe `Assessment`) e `risk → subject.state → risk` (a projeção guarda `RiskLevel`, e
`risk` já dependia de `subject` pelo `SubjectProfile` no `RiskContext`). Não havia arranjo dentro
de `subject` que resolvesse — uma projeção de risco precisa de `RiskLevel` por definição. Ficou em
módulo próprio `com.barrier.riskengine.riskstate`, do qual ninguém depende, ligado ao pipeline por
inversão: `AssessmentCompletedListener` declarada em `assessment` e implementada por
`SubjectRiskStateProjector`, mesmo padrão de `AssuranceRecordedListener`. O texto abaixo é o plano
original, preservado.

**Por que `subject.state` e não dentro de `subject.profile`:** o cadastro é dossiê declarado
pelo parceiro; a projeção é resultado do motor. Responsabilidades distintas, ciclos de vida
distintos, e juntar as duas colocaria `subject.profile` dependendo de `risk`.

---

## Task 1 — Tabela e repositório da projeção

**Files:**
- Create: `services/risk-engine/src/main/resources/db/migration/V041__subject_risk_state.sql`
- Create: `subject/state/domain/SubjectRiskState.java`
- Create: `subject/state/repository/SubjectRiskStateEntity.java`,
  `SubjectRiskStateRepositoryImpl.java`,
  `interfaces/SubjectRiskStateJpaRepository.java`, `interfaces/SubjectRiskStateRepository.java`
- Test: `subject/state/repository/SubjectRiskStateRepositoryIT.java`

**Interfaces produzidas:**
```java
public record SubjectRiskState(
    UUID subjectId, String tenantId, RiskLevel level, int score,
    AssessmentStatus decision, String assessmentId, String engineVersion,
    Instant evaluatedAt, Instant updatedAt) {}

public interface SubjectRiskStateRepository {
  Optional<SubjectRiskState> find(UUID subjectId, String tenantId);
  void save(SubjectRiskState state);
}
```

**Chave é `(subject_id, tenant_id)`, não `subject_id`.** Projeção global vazaria risco entre
parceiros — a decisão de aceitar/recusar é por tenant no assessment (ADR-0011/0012), e o mesmo
subject pode estar APROVADO num parceiro e REPROVADO em outro. Projeção global teria que
escolher um, e escolheria errado para o outro.

- [ ] **Passo 1: migration**

```sql
-- Risco corrente do cliente, por tenant.
--
-- `risk_scores` guarda uma linha por avaliação e nunca é sobrescrito — é a trilha, e continua
-- sendo. O que não existia era a resposta para "qual é o risco deste cliente agora": era preciso
-- caçar a última avaliação concluída, e nada no código fazia isso. Sem essa projeção não há como
-- responder "meus clientes em CRITICAL", avisar o parceiro quando o risco muda, nem dizer ao
-- rescreening o que mudou em relação a antes.
--
-- A chave é (subject_id, tenant_id) e não subject_id: o mesmo cliente pode estar aprovado num
-- parceiro e reprovado em outro, e uma projeção global teria que escolher um dos dois.
CREATE TABLE subject_risk_state (
    subject_id     UUID         NOT NULL REFERENCES subjects (id),
    tenant_id      VARCHAR(40)  NOT NULL REFERENCES tenants (id),
    risk_level     VARCHAR(20)  NOT NULL,
    risk_score     INTEGER      NOT NULL,
    decision       VARCHAR(30)  NOT NULL,
    assessment_id  VARCHAR(40)  NOT NULL,
    engine_version VARCHAR(60),
    evaluated_at   TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (subject_id, tenant_id)
);

-- Responde "meus clientes em CRITICAL" sem varrer a tabela.
CREATE INDEX idx_subject_risk_state_level ON subject_risk_state (tenant_id, risk_level);

-- Backfill: a última avaliação concluída de cada (subject, tenant) é exatamente o que a projeção
-- teria gravado se existisse desde o início. Sem isto, todo cliente já avaliado apareceria como
-- "sem risco corrente" até ser reavaliado — e o fallback do GET esconderia o buraco em vez de
-- fechá-lo.
INSERT INTO subject_risk_state (
    subject_id, tenant_id, risk_level, risk_score, decision,
    assessment_id, engine_version, evaluated_at, updated_at)
SELECT DISTINCT ON (a.subject_id, a.tenant_id)
       a.subject_id, a.tenant_id, a.risk_level,
       COALESCE(rs.total_score, 0), a.status,
       a.id, rs.engine_version, a.completed_at, NOW()
  FROM assessments a
  LEFT JOIN risk_scores rs ON rs.assessment_id = a.id
 WHERE a.completed_at IS NOT NULL
   AND a.risk_level IS NOT NULL
   AND a.subject_id IS NOT NULL
 ORDER BY a.subject_id, a.tenant_id, a.completed_at DESC;
```

- [ ] **Passo 2: teste de integração do repositório** (Testcontainers, padrão dos ITs
  existentes): salvar, ler de volta, e provar que a segunda gravação do mesmo par
  `(subject, tenant)` **substitui** em vez de duplicar (a PK composta é o que garante).
- [ ] **Passo 3: rodar, ver falhar**
- [ ] **Passo 4: `SubjectRiskState`, entity, impl e interfaces** no padrão de
  `SubjectProfileRepositoryImpl` (domínio puro, entity com Lombok `AccessLevel.PACKAGE`).
- [ ] **Passo 5: rodar, ver passar**
- [ ] **Passo 6: commit** — `feat(subject): projecao de risco corrente por tenant`

---

## Task 2 — Upsert monotônico

**Files:**
- Create: `subject/state/service/SubjectRiskStateService.java`,
  `subject/state/domain/RiskLevelTransition.java`
- Test: `subject/state/service/SubjectRiskStateServiceTest.java`

**Interfaces produzidas:**
```java
public record RiskLevelTransition(RiskLevel from, RiskLevel to) {}

/** @return a transição, se o nível mudou; vazio se não mudou ou se o upsert foi ignorado */
Optional<RiskLevelTransition> record(Assessment assessment, int score, String engineVersion);
Optional<SubjectRiskState> find(UUID subjectId, String tenantId);
```

**A regra que mais fácil se erra — e é o motivo desta tarefa existir separada:** a projeção é
monotônica no **tempo da avaliação**, não no tempo do commit. Rescreening, reavaliação por
assurance e decisão manual podem concluir fora de ordem; uma avaliação iniciada antes e
concluída depois não pode sobrescrever um estado mais novo. O upsert só grava se
`evaluatedAt` for **posterior** ao `evaluatedAt` já persistido.

- [ ] **Passo 1: os três testes que definem o comportamento**

```java
@Test
void primeira_avaliacao_cria_o_estado_e_nao_reporta_transicao() { /* from == null → sem evento */ }

@Test
void avaliacao_mais_nova_sobrescreve_e_reporta_a_transicao() { /* LOW → HIGH */ }

@Test
void avaliacao_concluida_fora_de_ordem_nao_sobrescreve_estado_mais_novo() {
  // grava avaliação com evaluatedAt = agora; depois grava outra com evaluatedAt = agora-1h
  // e o estado permanece o da primeira, sem transição reportada
}
```

**Primeira avaliação não reporta transição** de propósito: `null → LOW` não é "o risco do
cliente mudou", é "o cliente passou a existir". Emitir evento aí transformaria todo onboarding
em duas notificações para o parceiro, uma delas redundante com o `assessment.completed` que ele
já recebe.

- [ ] **Passo 2: rodar, ver falhar**
- [ ] **Passo 3: implementar** — `record` lê o estado atual, compara `evaluatedAt`, grava e
  devolve a transição só quando `from != to` e ambos existem.
- [ ] **Passo 4: rodar, ver passar**
- [ ] **Passo 5: commit** — `feat(subject): upsert monotonico do risco corrente`

---

## Task 3 — Ligar ao pipeline

**Files:**
- Modify: `assessment/service/AssessmentProcessor.java` (bloco transacional da conclusão)
- Modify: `assessment/service/AssessmentService.java` (decisão manual)
- Test: `assessment/service/AssessmentProcessorRiskStateTest.java`,
  `assessment/service/AssessmentServiceDecisionRiskStateTest.java`

**A projeção grava na mesma transação da conclusão** — é projeção, não evento: se a avaliação
commitou, o estado corrente commitou. Fora da transação, uma falha entre as duas gravações
deixaria a projeção mentindo indefinidamente, sem nada que a reconcilie.

**A decisão manual também atualiza.** Sem isso o corrente fica preso no que o motor decidiu
antes do analista: um cliente que o motor mandou para EM_REVISAO e o analista reprovou
apareceria na projeção com a decisão do motor, e o `GET` responderia o contrário do que o
parceiro decidiu. Ver [ADR-0017](../adr/0017-ownership-de-recovery.md) para o vocabulário de
quem é dono de quê — a projeção não tem recovery próprio, ela segue a transação da avaliação.

- [ ] **Passo 1: testes** — conclusão pelo processor grava a projeção; `decide(APPROVE)` a
  partir de EM_REVISAO atualiza a decisão corrente.
- [ ] **Passo 2: rodar, ver falhar**
- [ ] **Passo 3: implementar** — chamada dentro do `transactionTemplate.executeWithoutResult`
  já existente, ao lado de `repository.save(assessment)`.
- [ ] **Passo 4: rodar, ver passar**
- [ ] **Passo 5: commit** — `feat(assessment): atualiza o risco corrente ao concluir e ao decidir`

---

## Task 4 — `GET /v1/subjects/{document}/risk-state`

**Files:**
- Create: `subject/state/controller/SubjectRiskStateController.java`,
  `controller/dto/SubjectRiskStateResponse.java`
- Test: `subject/state/controller/SubjectRiskStateControllerIT.java`

Escopo por tenant e **404 sem vínculo**, igual ao `GET /v1/subjects/{document}` — subject que
o tenant não conhece não existe para ele. Documento na resposta sempre mascarado.

**Fallback:** sem linha na projeção, cai na última avaliação concluída daquele
`(subject, tenant)` — mesmo desenho do `GET /risk/v1/clientes/{documento}/score` do Mishmar.
O backfill da V041 já preenche o histórico, então o fallback cobre só a janela entre a
migration e a próxima avaliação de um subject criado no meio do caminho.

- [ ] **Passo 1: teste de integração** — 200 com projeção; 200 pelo fallback; 404 sem vínculo.
- [ ] **Passo 2: rodar, ver falhar**
- [ ] **Passo 3: implementar**
- [ ] **Passo 4: rodar, ver passar**
- [ ] **Passo 5: commit** — `feat(subject): GET do risco corrente com fallback`

---

## Task 5 — Evento de mudança de nível (F4)

**Files:**
- Create: `subject/state/service/RiskLevelChangeEventPublisher.java`,
  `subject/state/service/RiskLevelChangedPayload.java`
- Modify: `AssessmentProcessor` / `AssessmentService` — publicam quando `record` devolve
  transição
- Test: `subject/state/service/RiskLevelChangeEventPublisherTest.java`

**Payload:**
```java
public record RiskLevelChangedPayload(
    String tenantId, String subjectId, String documentType, String maskedDocument,
    String previousLevel, String currentLevel, String decision,
    String assessmentId, String origin, String engineVersion, Instant changedAt) {}
```

**`origin` vai no payload de propósito.** Mudança de nível causada por reavaliação sem fato
novo não deveria acordar o parceiro no meio da noite, mas a política de notificação é dele, não
nossa — filtrar aqui seria decidir por ele. Levamos `ONBOARDING`/`RESCREENING`/`ASSURANCE` e ele
filtra.

Tópico `barrier.subject.risk_level_changed`, gravado na **outbox** na mesma transação da
projeção (`OutboxRecorder`, padrão do `AssessmentEventPublisher`).

- [ ] **Passo 1: teste** — transição grava exatamente um evento na outbox; nível repetido não
  grava nenhum; primeira avaliação não grava nenhum.
- [ ] **Passo 2: rodar, ver falhar**
- [ ] **Passo 3: implementar**
- [ ] **Passo 4: rodar, ver passar**
- [ ] **Passo 5: commit** — `feat(subject): evento de mudanca de nivel de risco`

---

## Task 6 — Entrega do evento novo (F4)

**Files:**
- Modify: `webhook-api` — listener do tópico novo, no padrão do listener de
  `assessment.completed` (idempotência por `eventId`, `DefaultErrorHandler`, DLT)
- Test: `SubjectRiskLevelDeliveryIT.java`

O endpoint por tenant (`webhook_endpoints`), o HMAC e o retry já existem e não mudam: o que
entra é mais um tipo de evento chegando na mesma máquina de entrega.

**Ponto de atenção do reconciliador:** o `DeliveryReconciliationJob` relê **apenas**
`barrier.assessment.completed` (constante `TOPIC` na classe). O evento novo fica sem
reconciliação. Duas saídas, e a escolha é do executor com o [ADR-0017](../adr/0017-ownership-de-recovery.md)
na mão: generalizar o job para uma lista de tópicos (e atualizar a tabela de ownership), ou
registrar explicitamente que mudança de nível não tem recuperação por reconciliação — o que é
defensável, já que a próxima mudança de nível reemite e o `GET` do Task 4 é a fonte de verdade
consultável. **O que não é aceitável é deixar implícito.**

- [ ] **Passo 1: teste de integração** (Testcontainers, Kafka + Postgres)
- [ ] **Passo 2: rodar, ver falhar**
- [ ] **Passo 3: implementar**
- [ ] **Passo 4: rodar, ver passar**
- [ ] **Passo 5: atualizar `CLAUDE.md`, `fila-origem.md` (F3/F4) e, se for o caso, a tabela do
  ADR-0017**
- [ ] **Passo 6: commit** — `feat(webhook): entrega do evento de mudanca de nivel`
