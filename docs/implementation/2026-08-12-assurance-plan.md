# Assurance (documentoscopia + biometria) — Plano de Implementação

> **Para executores:** use `superpowers:executing-plans` ou `superpowers:subagent-driven-development`.
> Passos usam checkbox (`- [ ]`).

**Goal:** Ligar a frente de assurance ao pipeline de decisão — endpoints de submissão com
consentimento, extração de campos, `AssuranceSummary` no processador e reavaliação automática.

**Architecture:** A submissão grava o `AssuranceCheck` e notifica listeners; o `rescreening`
implementa o listener e submete avaliação nova com `origin = ASSURANCE`. `assurance` não conhece
`assessment` — a inversão evita o ciclo `assurance → assessment → risk → assurance`.

**Tech Stack:** Java 25, Spring Boot 4.0, Flyway, JUnit 5, Mockito, AssertJ, Testcontainers, ArchUnit.

**Spec:** [2026-08-12-assurance-design.md](2026-08-12-assurance-design.md)

## Global Constraints

- Camadas `controller → service → repository`; integração externa só por interface (`client`).
  Validado por ArchUnit — 5/5 tem de continuar verde, incluindo `sem_ciclos_entre_modulos`.
- Migrations Flyway são imutáveis. A próxima livre é **V036**.
- Nunca logar CPF/CNPJ sem mascarar. Nunca persistir imagem, selfie ou template biométrico.
- `./mvnw spotless:apply` **não roda no JDK 25** — formatar à mão (2 espaços, 100 colunas).
- `JAVA_HOME=C:\Users\leona\.jdks\corretto-25.0.3` antes de qualquer `mvnw`.
- Bug corrigido vem com teste. TDD: teste vermelho **visto falhar** antes do código.
- Rodar testes: `Set-Location C:\Dev\barrier; $env:JAVA_HOME="C:\Users\leona\.jdks\corretto-25.0.3"; .\mvnw.cmd -pl services/risk-engine -Dtest=<Classe> test`

---

### Task 1: Consentimento no `AssuranceCheck`

**Files:**
- Create: `services/risk-engine/src/main/resources/db/migration/V036__assurance_consent.sql`
- Create: `.../assurance/domain/AssuranceConsent.java`
- Modify: `.../assurance/domain/AssuranceCheck.java`
- Modify: `.../assurance/repository/AssuranceCheckRepositoryImpl.java`
- Test: `.../assurance/domain/AssuranceConsentTest.java`

**Interfaces:**
- Produces: `AssuranceConsent(String reference, String purpose, Instant grantedAt)` com
  `validate()` lançando `IllegalArgumentException`; `AssuranceCheck` ganha o componente
  `AssuranceConsent consent` como **último** parâmetro do record.

- [ ] **Step 1: escrever o teste que falha**

```java
@Test
void recusa_consentimento_sem_finalidade() {
  assertThatThrownBy(
          () -> new AssuranceConsent("ref-1", "  ", Instant.now()).validate())
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("finalidade");
}

@Test
void recusa_consentimento_no_futuro() {
  assertThatThrownBy(
          () ->
              new AssuranceConsent("ref-1", "KYC", Instant.now().plusSeconds(3600)).validate())
      .isInstanceOf(IllegalArgumentException.class);
}

@Test
void aceita_consentimento_completo() {
  assertThatCode(() -> new AssuranceConsent("ref-1", "KYC", Instant.now()).validate())
      .doesNotThrowAnyException();
}
```

- [ ] **Step 2: rodar e ver falhar** — `-Dtest=AssuranceConsentTest`. Esperado: erro de
      compilação (classe não existe). Criar o record vazio e rodar de novo até falhar por
      asserção, não por compilação.

- [ ] **Step 3: implementar `AssuranceConsent`** — record com os três campos e `validate()`.
      Javadoc tem de dizer **por que** o consentimento é por check e não por subject: a LGPD
      exige prova de consentimento para aquela finalidade no momento daquele tratamento.

- [ ] **Step 4: migration V036**

```sql
-- Consentimento é por verificação, não por cadastro: a prova que a LGPD pede é de consentimento
-- para AQUELA finalidade no momento DAQUELE tratamento. Um flag global no subject não prova isso.
ALTER TABLE identity_assurance_checks
    ADD COLUMN consent_reference  VARCHAR(120),
    ADD COLUMN consent_purpose    VARCHAR(120),
    ADD COLUMN consent_granted_at TIMESTAMPTZ;
```

Colunas nullable porque a V035 pode ter linhas em dev; a obrigatoriedade é imposta na borda
(Task 5), não no schema.

- [ ] **Step 5: adicionar `consent` ao record `AssuranceCheck`** e persistir/ler no
      `AssuranceCheckRepositoryImpl` (ler o arquivo antes: o INSERT e o RowMapper precisam das
      três colunas novas).

- [ ] **Step 6: rodar `-Dtest=AssuranceConsentTest`** — PASS. Depois `-Dtest=IdentityAssuranceRiskRuleTest`
      para garantir que o record novo não quebrou os 7 testes existentes.

- [ ] **Step 7: commit** — `feat(assurance): registra consentimento junto da verificação`

---

### Task 2: Provider devolve campos extraídos

**Files:**
- Create: `.../assurance/client/ExtractedDocumentFields.java`
- Create: `.../assurance/client/DocumentVerificationResult.java`
- Modify: `.../assurance/client/interfaces/DocumentVerificationProvider.java`
- Modify: `.../assurance/client/StubDocumentVerificationProvider.java`
- Modify: `.../assurance/service/AssuranceService.java`
- Test: `.../assurance/client/StubDocumentVerificationProviderTest.java`

**Interfaces:**
- Produces: `ExtractedDocumentFields(String name, String document, LocalDate birthDate)`;
  `DocumentVerificationResult(AssuranceCheck check, ExtractedDocumentFields extracted)`;
  `DocumentVerificationProvider.verify(...)` passa a devolver `DocumentVerificationResult`.
- Consumes: `AssuranceCheck` com `consent` (Task 1).

Mesmo par que `IdentityResult(check, company)` já usa — seguir aquele arquivo como referência.

- [ ] **Step 1: escrever o teste que falha** — o stub devolve check **e** campos extraídos, e
      `extracted` é nulo quando o desfecho não é `PASS` (documento reprovado não produz dado
      confiável).

```java
@Test
void documento_aprovado_devolve_campos_extraidos() {
  DocumentVerificationResult result =
      provider.verify(SUBJECT, "tenant-1", submissao("hash-ok"));

  assertThat(result.check().outcome()).isEqualTo(AssuranceOutcome.PASS);
  assertThat(result.extracted()).isNotNull();
  assertThat(result.extracted().birthDate()).isNotNull();
}

@Test
void documento_reprovado_nao_devolve_campos() {
  DocumentVerificationResult result =
      provider.verify(SUBJECT, "tenant-1", submissao("hash-fail"));

  assertThat(result.check().outcome()).isEqualTo(AssuranceOutcome.FAIL);
  assertThat(result.extracted()).isNull();
}
```

- [ ] **Step 2: rodar e ver falhar.**
- [ ] **Step 3: criar os dois records e mudar a interface.**
- [ ] **Step 4: ajustar o stub** — ler o `StubDocumentVerificationProvider` atual e preservar o
      esquema de cenários que ele já tiver; só acrescentar os campos extraídos.
- [ ] **Step 5: ajustar `AssuranceService.verifyDocument`** para devolver
      `DocumentVerificationResult` (persistindo só o check).
- [ ] **Step 6: rodar; PASS.** `-Dtest=StubDocumentVerificationProviderTest`
- [ ] **Step 7: commit** — `feat(assurance): documentoscopia devolve campos extraídos`

---

### Task 3: `AssessmentOrigin.ASSURANCE`

**Files:**
- Modify: `.../assessment/domain/assessment/AssessmentOrigin.java`
- Modify: `.../assessment/domain/assessment/Assessment.java:109` (região do factory de rescreening)
- Modify: `.../assessment/service/SubmitAssessmentCommand.java:63`
- Modify: `.../assessment/service/AssessmentService.java:110` (o `switch` sobre origin)
- Test: `.../assessment/service/AssessmentServiceTest.java`

**Interfaces:**
- Produces: `SubmitAssessmentCommand.assurance(tenantId, documentType, document, name, originDetail)`
  — mesma assinatura de `.rescreening(...)`, que já existe na linha 63.

- [ ] **Step 1: teste que falha** — submissão com origin `ASSURANCE` grava `origin` e
      `origin_detail`. Copiar o teste equivalente de `RESCREENING` que já existe no arquivo e
      adaptar; **não** inventar um formato novo.
- [ ] **Step 2: rodar e ver falhar.**
- [ ] **Step 3: acrescentar o valor no enum, o factory em `Assessment`, o factory em
      `SubmitAssessmentCommand` e o `case` no `AssessmentService`.** O `origin_detail` do
      assurance é `<kind>@<providerReference>` (ex.: `DOCUMENT@abc-123`) — espelha o
      `fonte@versão` do rescreening.
- [ ] **Step 4: rodar; PASS.**
- [ ] **Step 5: commit** — `feat(assessment): origin ASSURANCE`

---

### Task 4: Listener — a inversão que evita o ciclo

**Files:**
- Create: `.../assurance/service/AssuranceRecordedListener.java`
- Modify: `.../assurance/service/AssuranceService.java`
- Create: `.../rescreening/service/AssuranceReassessmentTrigger.java`
- Test: `.../assurance/service/AssuranceServiceTest.java`
- Test: `.../rescreening/AssuranceReassessmentTriggerTest.java`

**Interfaces:**
- Produces: `AssuranceRecordedListener.onRecorded(AssuranceCheck check)` — `void`.
- Consumes: `AssessmentService.submit(SubmitAssessmentCommand)` e
  `SubmitAssessmentCommand.assurance(...)` (Task 3).

⚠️ **Não** reusar `RescreeningService.submit(...)`: aquela assinatura é
`submit(MonitoredSubject, String, String)` e `MonitoredSubject` nasce de um match de watchlist —
não descreve uma verificação de documento. O trigger chama `AssessmentService.submit` direto, o
que é legítimo porque `rescreening → assessment` já é uma aresta existente. O trigger só mora em
`rescreening` para não criar `assurance → assessment`.

**Esta é a task cuja regressão o ArchUnit pega.** O pacote `assurance` **não pode** importar nada
de `assessment` nem de `rescreening`. Se precisar, o design está errado — pare e diga.

- [ ] **Step 1: teste que falha em `AssuranceServiceTest`** — todo desfecho notifica, e listener
      que lança não derruba a gravação:

```java
@Test
void notifica_listener_em_qualquer_desfecho() {
  service.verifyDocument(SUBJECT, "tenant-1", submissao());
  verify(listener).onRecorded(any(AssuranceCheck.class));
}

@Test
void falha_do_listener_nao_desfaz_a_verificacao() {
  doThrow(new RuntimeException("banco fora")).when(listener).onRecorded(any());

  assertThatCode(() -> service.verifyDocument(SUBJECT, "tenant-1", submissao()))
      .doesNotThrowAnyException();
  verify(repository).save(any(AssuranceCheck.class));
}
```

- [ ] **Step 2: rodar e ver falhar.**
- [ ] **Step 3: criar a interface e injetar `List<AssuranceRecordedListener>` no
      `AssuranceService`**, com try/catch **por listener** — mesmo padrão do
      `WatchlistImporter.notifyListeners`, que é a referência a copiar.
- [ ] **Step 4: rodar; PASS.**
- [ ] **Step 5: teste que falha para o `AssuranceReassessmentTrigger`** — recebe o check e chama
      `RescreeningService.submit` com `origin = ASSURANCE` e `originDetail = DOCUMENT@<ref>`.
- [ ] **Step 6: implementar o trigger** em `rescreening`.
- [ ] **Step 7: rodar `-Dtest=LayeredArchitectureTest`** — Esperado: **5/5 PASS**, sem
      `Architecture Violation`. É a prova de que a inversão funcionou.
- [ ] **Step 8: commit** — `feat(assurance): reavaliação por listener, sem ciclo entre módulos`

---

### Task 5: Endpoints de submissão

**Files:**
- Create: `.../assurance/controller/AssuranceController.java`
- Create: `.../assurance/controller/dto/SubmitDocumentRequest.java`
- Create: `.../assurance/controller/dto/SubmitBiometricRequest.java`
- Create: `.../assurance/controller/dto/AssuranceCheckResponse.java`
- Test: `.../assurance/controller/AssuranceControllerTest.java`

**Interfaces:**
- Consumes: `AssuranceService.verifyDocument/verifyBiometrics`, `AssuranceConsent` (Task 1).

Ler antes: `SubjectProfileController` — ele já resolve subject por documento exigindo vínculo do
tenant. **Reusar aquele caminho**, não escrever outro.

- [ ] **Step 1: testes que falham** — quatro casos:
  1. consentimento ausente → 400 e o provider **não** é chamado;
  2. subject sem vínculo com o tenant → 404 (não 403: 403 confirmaria que o cliente existe em
     outro parceiro);
  3. submissão válida → 200 com desfecho, provedor e id da reavaliação;
  4. a resposta **não** contém os campos extraídos do documento.
- [ ] **Step 2: rodar e ver falhar.**
- [ ] **Step 3: implementar controller e DTOs.** O controller **não** toca repositório (ArchUnit).
- [ ] **Step 4: rodar; PASS.**
- [ ] **Step 5: commit** — `feat(assurance): endpoints de submissão com consentimento`

---

### Task 6: Campos extraídos → verificação e fator de risco

**Files:**
- Modify: `.../subject/profile/domain/VerificationMethod.java` (acrescentar `DOCUMENT`)
- Modify: `.../subject/profile/service/FieldVerificationService.java`
- Modify: `.../assurance/service/AssuranceService.java`
- Modify: `.../risk/rule/IdentityAssuranceRiskRule.java`
- Test: `.../assurance/DocumentFieldExtractionTest.java`

**Interfaces:**
- Produces: `FieldVerificationService.recordBirthDateFromDocument(subjectId, tenantId, birthDate, providerReference)`
  — espelhar `recordBirthDateFromBureau`, que já existe na linha 132 e é a referência exata.

- [ ] **Step 1: testes que falham:**
  1. nascimento extraído **igual** ao declarado → grava `FieldVerification` com
     `method = DOCUMENT` e `evidence = providerReference`;
  2. nascimento extraído **diferente** do declarado → **não** grava verificação e produz fator de
     risco;
  3. nome extraído diferente do declarado → fator de risco;
  4. `extracted == null` (documento reprovado) → não grava nada, não quebra.
- [ ] **Step 2: rodar e ver falhar.**
- [ ] **Step 3: acrescentar `DOCUMENT` ao enum e `recordBirthDateFromDocument` ao serviço.**
- [ ] **Step 4: ligar a extração no `AssuranceService`** e o fator de divergência na regra.
- [ ] **Step 5: rodar; PASS.**
- [ ] **Step 6: commit** — `feat(assurance): documento verifica nascimento e sinaliza divergência`

---

### Task 7: `AssuranceSummary` no pipeline

**Files:**
- Modify: `.../assessment/service/AssessmentProcessor.java:220`
- Modify: `.../risk/service/RiskScoringService.java` (constante `ENGINE_VERSION`)
- Test: `.../assessment/service/AssessmentProcessorTest.java`

- [ ] **Step 1: teste que falha** — o processador monta o `AssuranceSummary` a partir de
      `latest(DOCUMENT)`, `latest(BIOMETRIC)` e `attempts(BIOMETRIC)`, e a
      `IdentityAssuranceRiskRule` dispara para um subject com biometria `FAIL`.
- [ ] **Step 2: rodar e ver falhar** — hoje falha porque o `RiskContext` de 6 argumentos deixa
      `assurance` nulo. **Esse é o bug que esta task fecha; confirme que a falha é essa.**
- [ ] **Step 3: trocar para o `RiskContext` de 7 argumentos**, buscando o summary uma vez só
      (o `profile` já é buscado uma vez e reaproveitado — seguir o mesmo cuidado).
- [ ] **Step 4: subir `ENGINE_VERSION` para `barrier-risk-rules/1.7.0`.** Regra que passa a
      disparar muda a decisão para o mesmo insumo; não subir mentiria na auditoria.
- [ ] **Step 5: rodar; PASS.** Rodar também `-Dtest=RiskScoringServiceTest` — a constante
      provavelmente é asserida lá.
- [ ] **Step 6: commit** — `feat(risk): assurance entra no motor; ENGINE_VERSION 1.7.0`

---

### Task 8: Integração do SQL de assurance

**Files:**
- Test: `.../assurance/AssuranceCheckRepositoryIntegrationTest.java`

O SQL da V035 **nunca rodou contra Postgres**. Seguir o padrão de Testcontainers dos testes de
integração que já existem (ex.: `TenantIsolationIntegrationTest`). Docker precisa estar de pé.

- [ ] **Step 1: escrever o teste** — salvar e reler preservando todos os campos (inclusive
      consentimento e `algorithm_version`); `findLatest` devolvendo o mais recente por
      `(subject, tenant, kind)`; `findAll` não vazando check de outro tenant.
- [ ] **Step 2: rodar.** Se falhar, é bug real de SQL/mapeamento — corrigir o repositório, não o
      teste.
- [ ] **Step 3: commit** — `test(assurance): integração do repositório contra Postgres`

---

### Task 9: Fluxo ponta a ponta

**Files:**
- Test: `.../assurance/AssuranceFlowIntegrationTest.java`

- [ ] **Step 1: escrever o teste** — avaliação que cai em `SOLICITAR_DOCUMENTO`; `POST` de
      documento com consentimento; assertar que nasceu uma segunda avaliação com
      `origin = ASSURANCE`, que a primeira **não** foi mutada, e que o desfecho reflete o
      resultado da verificação.
- [ ] **Step 2: rodar; corrigir o que aparecer.**
- [ ] **Step 3: commit** — `test(assurance): fluxo ponta a ponta`

---

### Task 10: ADR-0016 e documentação

**Files:**
- Modify: `docs/adr/0016-plataforma-completa-modelo-b.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: corrigir o ADR** — status `Proposto` → `Aceito`; etapa 2 deixa de ser
      pré-requisito bloqueante da etapa 3 (razão: sem imagem nem template, o que fica no banco é
      dado pessoal comum, do mesmo nível do `raw_response` e do segredo HMAC que já estão sem
      cifragem); etapa 1 consta concluída; consentimento atendido pelo registro na submissão.
      **Não apagar o texto original da ordenação** — registrar a correção como correção, que é o
      que um ADR serve para preservar.
- [ ] **Step 2: atualizar o CLAUDE.md** — seção de assurance no "Estado atual",
      `ENGINE_VERSION` 1.7.0, e o baseline de testes (hoje diz "275 na risk-engine + 16 na
      webhook-api"; o real antes desta frente era 369 + 53).
- [ ] **Step 3: rodar a suíte completa** — `.\mvnw.cmd test`. Esperado: BUILD SUCCESS.
- [ ] **Step 4: commit** — `docs: corrige ordenação do ADR-0016 e registra assurance`

---

## Verificação final

```bash
cd C:\Dev\barrier && set JAVA_HOME=C:\Users\leona\.jdks\corretto-25.0.3 && mvnw.cmd test
```

1. BUILD SUCCESS, zero falhas.
2. `LayeredArchitectureTest` da risk-engine **5/5**, sem `Architecture Violation` — em especial
   `sem_ciclos_entre_modulos`, que é o que prova a inversão da Task 4.
3. `grep -r "AssuranceSummary" AssessmentProcessor.java` retorna resultado — a regra deixou de
   ser código morto.
