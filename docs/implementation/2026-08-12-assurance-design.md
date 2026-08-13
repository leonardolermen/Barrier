# Design: terminar a frente de assurance (documentoscopia + biometria)

- **Data:** 2026-08-12
- **Relacionado:** [ADR-0016](../adr/0016-plataforma-completa-modelo-b.md) (etapa 3), que este
  trabalho também corrige

## Problema

O código de assurance existe e ninguém o chama. `AssuranceService`, `AssuranceCheck`, os dois
providers atrás de interface, o repositório, a migration V035 e a `IdentityAssuranceRiskRule`
com 7 testes passando — tudo pronto, e o `AssessmentProcessor` monta o `RiskContext` com 6
argumentos, deixando `assurance` nulo. A regra nunca dispara em produção.

É o mesmo padrão de falha que a `PepRiskRule` teve antes da CGU: controle que existe, tem teste,
e não roda. O estado é pior que ausência — parece pronto no repositório e dá conforto falso.

Faltam quatro coisas: endpoints de submissão, o `AssuranceSummary` preenchido no processador,
extração de campos do documento, e teste de integração do SQL.

## Decisões

Três bifurcações, decididas antes do design:

1. **A submissão dispara reavaliação**, não recalcula a avaliação existente. Nova avaliação pelo
   pipeline normal, `origin = ASSURANCE`. Não há caminho paralelo de decisão, e a trilha preserva
   as duas avaliações — a que pediu o documento e a que decidiu com ele. Recalcular in-place
   daria a `Assessment` um segundo caminho de mutação além do review humano.
2. **Consentimento é registrado na submissão** — referência, finalidade e timestamp, obrigatórios,
   persistidos junto do check. O ADR-0016 trata a ausência de registro de consentimento como
   bloqueio intencional da etapa 3; sem isto, a frente ficaria destravada só no papel.
3. **Campos extraídos comparam e registram verificação.** Divergência vira fator de risco;
   convergência grava `FieldVerification`. Não escreve no `SubjectProfile`.

## Restrição de arquitetura

A submissão precisa disparar reavaliação, que vive em `assessment`. Mas `IdentityAssuranceRiskRule`
(em `risk`) importa `assurance.domain`. Uma aresta `assurance → assessment` fecharia o ciclo
`assurance → assessment → risk → assurance`.

Solução, reusando o padrão estabelecido no mesmo dia para o rescreening: `assurance` declara
`AssuranceRecordedListener` e não conhece quem reage. A implementação vive em **`rescreening`**,
que já é "fato novo sobre um subject dispara reavaliação" e já tem `RescreeningService.submit`.
Documentoscopia concluída é um fato novo como uma lista nova é.

Aresta resultante: `rescreening → assurance`, na mesma direção de `rescreening → screening`.

## Componentes

### Submissão

```
POST /v1/subjects/{document}/assurance/document
POST /v1/subjects/{document}/assurance/biometric
```

> **Correção (revisão final):** o texto abaixo descrevia o escopo por `X-Client-Id`, anterior à
> troca para autenticação por credencial. Escopo por tenant vem de `Authorization: Bearer` via
> `TenantAuthenticationFilter` — é o parceiro submetendo pelo cliente final dele, não operação
> admin, então segue a pré-auth do `POST /v1/assessments` e não o `X-Admin-Key`.

Resolvem o subject pelo documento **exigindo vínculo do tenant**; 404 sem vínculo, mesma regra do
`GET /v1/subjects/{documento}` — sem isso, um parceiro submeteria verificação sobre cliente de
outro e descobriria da existência dele pela resposta.

Corpo: os `DocumentSubmission`/`BiometricSubmission` já existentes (`captureReference`,
`submittedHash` — nunca imagem) mais o bloco de consentimento. Ausente ou incompleto → 400.

Resposta: o `AssuranceCheck` gravado (desfecho, score, provedor, referência, versão do algoritmo).
Nunca os campos extraídos do documento em si — devolvê-los transformaria o endpoint num serviço
de OCR sobre documento alheio.

> **Correção (revisão final):** o texto acima previa devolver também o id da reavaliação. Não
> devolve, e não é omissão: `AssuranceService.scheduleNotification` agenda a notificação para
> `TransactionSynchronization.afterCommit()`, e o listener que cria a reavaliação
> (`AssuranceReassessmentTrigger`) roda depois do commit da requisição HTTP — o id da nova
> avaliação simplesmente não existe ainda no instante em que a resposta é montada. A decisão de
> disparar a reavaliação já está registrada (e é deliberada, ver "A reavaliação dispara em
> qualquer desfecho" acima); o parceiro fica sabendo do resultado dela pelo canal de sempre
> (webhook), não pela resposta síncrona deste endpoint.

**A reavaliação dispara em qualquer desfecho**, não só em `PASS`. Um `FAIL` de prova de vida é
justamente o insumo que mais muda a decisão, e disparar só no sucesso deixaria a avaliação parada
em `SOLICITAR_DOCUMENTO` exatamente no caso de fraude. `UNAVAILABLE` também dispara: o motor já
sabe tratar indisponibilidade de provedor, e a `IdentityAssuranceRiskRule` tem caso para isso.

Migration **V036**: colunas de consentimento em `identity_assurance_checks`
(`consent_reference`, `consent_purpose`, `consent_granted_at`). Amarrado ao check, não ao
cadastro: a LGPD exige prova de consentimento para aquela finalidade no momento daquele
tratamento, e um flag global no subject não prova isso.

### Extração de campos

`DocumentVerificationProvider.verify` passa a devolver `DocumentVerificationResult(check,
extracted)` — mesmo par que `IdentityResult(check, company)` já usa. Os campos extraídos não são
persistidos como tal.

- Nascimento que confere com o declarado → `FieldVerification(BIRTH_DATE, method = DOCUMENT,
  evidence = providerReference)` via `FieldVerificationService`, espelhando o
  `recordBirthDateFromBureau` que já existe. Novo valor em `VerificationMethod`.
- Nome ou nascimento divergentes → fator de risco explicável. `VerifiableField` não os cobre
  porque nome é do `Subject`, não do cadastro: divergência aqui é sinal de fraude, não campo
  faltando.

> **Correção (revisão final):** o texto acima dizia "nome ou documento". O número do documento
> apresentado (RG/CNH) nunca entrou nessa comparação — foi removido de propósito, porque não é
> comparável com o CPF/CNPJ que identifica o `Subject` (ADR-0011): são grandezas diferentes, e
> compará-las geraria divergência sistemática (todo RG "diverge" do CPF do cadastro). A
> comparação real é nome e **nascimento** — ver `DivergentField`, que não tem valor `DOCUMENT`.

### Pipeline

`AssessmentProcessor` monta o `AssuranceSummary` a partir de `AssuranceService.latest(DOCUMENT)`,
`latest(BIOMETRIC)` e `attempts(BIOMETRIC)` — os três métodos já existem — e passa o `RiskContext`
de 7 argumentos.

`AssessmentOrigin.ASSURANCE` + `SubmitAssessmentCommand.assurance(...)`, espelhando o que a V032
fez para `RESCREENING`, incluindo o `case` no `AssessmentService`.

**`ENGINE_VERSION` sobe para `barrier-risk-rules/1.7.0`.** Uma regra que nunca disparou passando a
disparar muda a decisão para o mesmo insumo; não subir mentiria na auditoria.

## Correção do ADR-0016

Três pontos, e o primeiro é uma correção de mérito:

1. **A etapa 2 (cifragem em repouso) deixa de ser pré-requisito bloqueante da etapa 3.** O
   sequenciamento original existia porque uma base biométrica em texto puro é catastrófica.
   Com a decisão de não guardar imagem nem template, o que fica no banco é desfecho, score e
   referência do provedor — dado pessoal comum, do mesmo nível do `raw_response` (V031) e do
   segredo de HMAC que já estão lá sem cifragem. Cifragem continua necessária e continua no
   plano; deixa de ser bloqueio.
2. **A etapa 1 consta como concluída** — `subject_field_verifications`, `FieldVerification`,
   `FieldVerificationService`, OTP e o gate de completude exigindo verificação já existem.
3. **O bloqueio por consentimento passa a estar atendido** pelo registro obrigatório na submissão.

O ADR muda de `Proposto` para `Aceito`.

## Testes

TDD em tudo — teste vermelho antes de cada mudança de produção.

- Unitários: serviço de submissão (consentimento ausente rejeita e não chama o provider),
  extração (divergência vira fator, convergência grava verificação), o listener, e o
  `AssessmentProcessor` montando o summary.
- **Integração com Testcontainers do `AssuranceCheckRepositoryImpl`** — o SQL da V035 nunca rodou
  contra Postgres.
- **Fluxo ponta a ponta:** avaliação em `SOLICITAR_DOCUMENTO` → submissão de documento →
  reavaliação criada com `origin = ASSURANCE` e desfecho diferente.
- ArchUnit tem de continuar 5/5, incluindo a ausência de ciclos: é a prova de que o listener
  resolveu a inversão.

## Suposições

- Nenhum provedor real de documentoscopia/biometria contratado; os stubs seguem sendo as únicas
  implementações em desenvolvimento.

> **Correção (revisão final):** este item dizia que `AssuranceProviderReadinessGuard` "já existe
> para barrar prod". Não barra mais nada, e essa mudança é deliberada, não regressão: os stubs
> são `@Profile("!prod")`, então em produção eles não existem como bean — quem preenche o lugar é
> `UnavailableDocumentVerificationProvider`/`UnavailableBiometricVerificationProvider`
> (`@Profile("prod")`), que sempre devolvem `UNAVAILABLE`. Não há mais provedor simulado para
> barrar em `prod`; o guard passou a só **avisar** em log quando é o provedor de emergência que
> está ativo (nenhum contrato real firmado), no padrão de `CnpjBureauReadinessGuard`. Quem lê
> esta seção sem essa correção suporia produção protegida por um gate de startup que não existe
> mais nesse formato.
- A submissão é do parceiro, não do cliente final direto.

## Fora de escopo

- Cifragem em repouso (segue na Fase 6, agora sem bloquear esta frente).
- Subsistema completo de consentimento (revogação, expiração, gestão por finalidade).
- UBO além do 1º grau (etapa 4 do ADR-0016).
