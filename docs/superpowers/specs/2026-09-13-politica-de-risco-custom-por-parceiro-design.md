# Política de risco custom por parceiro — desenho

**Data:** 2026-09-13
**Estado:** aprovado em brainstorming, pendente de plano de implementação
**Escopo deste documento:** P1 do programa "Risk Control Plane" (ver §4)

---

## 1. Contexto e problema

> Nota: `Barrier_Risk_Infrastructure_Strategy.docx` é um documento de estratégia fornecido pelo
> autor deste projeto, **não versionado neste repositório**.

O `Barrier_Risk_Infrastructure_Strategy.docx` propõe reposicionar o Barrier de plataforma de
KYC/PLD-FT para *Risk Decisioning Platform*, com três wedges (KYC, Pix, KYB) sobre um núcleo
comum. A tese é a mesma do [ADR-0020](../../adr/0020-posicionamento-motor-de-decisao-api-first.md)
e não está em disputa.

O que o documento acrescenta de novo, e o que motiva este spec, é **política de risco escrita
pelo parceiro**: o time de risco do cliente da API define suas próprias regras e as ativa sem
depender de deploy do Barrier nem de engenharia do parceiro.

Hoje isso não existe em nenhum grau útil. O que existe é:

- `tenant_risk_config` (V015), que calibra **parâmetros** de duas regras de apetite
  (`NEW_COMPANY`, `SENSITIVE_CNAE`), por operação administrativa interna, não self-service.
- `risk_rule_registry` (V016), que liga e desliga **famílias inteiras** do motor, globalmente.

Nenhum dos dois permite ao parceiro expressar uma regra que o Barrier não tenha escrito antes.

## 2. Decisões tomadas no brainstorming

Quatro, todas do dono do produto:

1. **Autoridade: só aditiva, piso regulatório intocável.** Regra custom pode somar pontuação,
   forçar REVIEW ou forçar REJECT. Nunca remove, rebaixa ou zera o resultado de uma regra do
   motor, e em especial de uma regra regulatória (`RegulatoryRiskRules`).
2. **Primeira entrega sobre o contexto que já existe** (`RiskContext`: identidade, screening,
   perfil de PJ, cadastro, assurance). Nenhum caminho de execução novo. A linguagem, porém, não
   pode acoplar no formato do `RiskContext`, para que o domínio transacional entre depois como
   extensão e não como reescrita.
3. **Representação: árvore de predicados sobre um catálogo de campos versionado.** Sem parser de
   texto na primeira entrega.
4. **Decomposição em cinco specs** (§4), sendo este o primeiro.

## 3. Por que isto não contraria a recusa registrada

O arquivo registra duas recusas relevantes, e é obrigação deste documento argumentar contra o
racional delas em vez de ignorá-lo
([archive/README](../../implementation/archive/README.md),
[plano-auditoria](../../implementation/archive/plano-auditoria-2026-08-18.md),
[fila-origem F8](../../implementation/archive/fila-origem.md)).

### 3.1 "Dá ao regulado o botão de afrouxar o próprio controle de PLD-FT"

O argumento é forte e continua valendo — **para regra subtrativa**. A decisão 1 elimina o caso:
regra custom não tem como afrouxar nada. O compliance officer do comprador continua sem o botão
de relaxar, e ganha um de apertar, que é o que ele quer.

Vale notar que a recusa foi escrita olhando para regra de onboarding, onde o piso é legal. O
exemplo que motiva o documento de estratégia ("beneficiário novo + R$ 5.000 + troca de device")
não tem piso legal nenhum: é apetite de risco puro, sobre perda que é do parceiro.

### 3.2 "Regra-como-dado sacrificaria o `ENGINE_VERSION` e a trilha reproduzível"

Este argumento envelheceu, e envelheceu por causa do módulo `replay`.

O replay registrou como limitação aceita que **a decisão de `barrier-risk-rules/1.4.0` é
irreproduzível**, porque o código daquelas regras não está mais no binário e regra versionada
carregável em runtime foi recusada. Uma política que é artefato **imutável e versionado**,
gravada junto da decisão, não tem esse problema: ela pode ser reexecutada para sempre.

Regra como dado, nesta forma, é **mais** replayável que regra como código. O `ENGINE_VERSION`
continua cobrindo código; a decisão passa a gravar também a versão da política. Dois eixos de
versão, os dois imutáveis.

O que a recusa corretamente temia era regra-como-dado **mutável** em documento editável em
runtime (`expr-lang` no `tzofe`). Esse desenho continua recusado aqui.

## 4. Decomposição do programa

Este spec cobre **P1** apenas. Os demais são specs próprios, cada um com seu ciclo.

| # | Entrega | Depende de |
|---|---|---|
| **P1** | **Política custom sobre o `RiskContext` atual** | — |
| P2 | Shadow mode e backtesting (trava de ativação) | P1 |
| P3 | Decisão síncrona transacional + agregados sobre `behavior_events` | P1 |
| P4 | Fonte de mudança societária (fecha o KYB contínuo) | — |
| P5 | Resposta a fraude Pix (DICT/MED sobre a mesa) | P3 |

P1 entrega o diferencial comercial inteiro sem arquitetura nova, e serve para testar a hipótese
mais arriscada do documento de estratégia: **o time de risco do parceiro realmente escreve e
ativa política?** Essa hipótese é barata de testar sobre o pipeline que já está verde, e cara de
testar junto com um caminho de execução novo.

## 5. Arquitetura

### 5.1 Catálogo de campos

O catálogo é a **interface pública** da linguagem, e o que a desacopla do formato do
`RiskContext`.

Cada campo declara:

- **Identificador estável** (`company.openingDate`) — o nome que o parceiro escreve.
- **Tipo** (`DATE`, `NUMBER`, `STRING`, `ENUM`, `BOOLEAN`, `LIST`).
- **`ContextInput` de origem** — é daqui que o `requires()` da política é derivado.
- **Extrator** a partir do `RiskContext`.
- **Exposição em evidência**: `BY_VALUE` ou `OUTCOME_ONLY`.

A última é a defesa de privacidade. Campo marcado `OUTCOME_ONLY` nunca tem o valor escrito na
evidência: registra-se apenas o resultado da comparação. É a mesma regra que hoje permite que um
alerta do módulo `monitoring` circule em canal com controle de acesso mais fraco que o do banco.

**Catálogo v1** (derivado das formas reais em `RiskContext`):

| Campo | Tipo | Insumo | Evidência |
|---|---|---|---|
| `identity.status` | ENUM `IdentityStatus` | IDENTITY | BY_VALUE |
| `identity.documentType` | STRING | IDENTITY | BY_VALUE |
| `identity.provider` | STRING | IDENTITY | BY_VALUE |
| `screening.status` | ENUM `ScreeningStatus` | SCREENING | BY_VALUE |
| `screening.hits` | LIST | SCREENING | — (LIST nunca é BY_VALUE) |
| `screening.hits[].type` | ENUM `MatchType` | SCREENING | BY_VALUE |
| `screening.hits[].basis` | ENUM `MatchBasis` | SCREENING | BY_VALUE |
| `screening.hits[].party` | ENUM `ScreenedParty.Role` | SCREENING | BY_VALUE |
| `screening.hits[].source` | STRING | SCREENING | BY_VALUE |
| `company.openingDate` | DATE | COMPANY | BY_VALUE |
| `company.cnaeCode` | STRING | COMPANY | BY_VALUE |
| `company.partners` | LIST | COMPANY | — (LIST nunca é BY_VALUE) |
| `company.partners[].legalEntity` | BOOLEAN | COMPANY | BY_VALUE |
| `company.partners[].foreign` | BOOLEAN | COMPANY | BY_VALUE |
| `company.partners[].qualification` | STRING | COMPANY | BY_VALUE |
| `profile.birthDate` | DATE | PROFILE | OUTCOME_ONLY |
| `profile.foundingDate` | DATE | PROFILE | BY_VALUE |
| `profile.nationality` | STRING | PROFILE | BY_VALUE |
| `profile.occupation` | STRING | PROFILE | BY_VALUE |
| `profile.declaredIncome` | NUMBER | PROFILE | OUTCOME_ONLY |
| `profile.shareCapital` | NUMBER | PROFILE | BY_VALUE |
| `profile.cnaeCode` | STRING | PROFILE | BY_VALUE |
| `profile.address.state` | STRING | PROFILE | BY_VALUE |
| `profile.address.city` | STRING | PROFILE | BY_VALUE |
| `assurance.document.outcome` | ENUM `AssuranceOutcome` | ASSURANCE | BY_VALUE |
| `assurance.biometric.outcome` | ENUM `AssuranceOutcome` | ASSURANCE | BY_VALUE |
| `assurance.biometricAttempts` | NUMBER | ASSURANCE | BY_VALUE |

**Deliberadamente fora do catálogo:** `identity.documentDigits`, `identity.name`,
`identity.rawResponse`, `identity.providerReference`, `screening.hits[].matchedName`,
`company.partners[].name`, `profile.phone`, `profile.email`, `profile.address.street`,
`profile.address.number`, `profile.legalRepresentativeName`,
`profile.legalRepresentativeDocument`. São dado pessoal direto ou rastro interno; nenhuma regra de
apetite legítima precisa comparar contra eles, e expô-los criaria um caminho de exfiltração via
evidência de política.

**Campo pode ser marcado obsoleto, nunca removido.** Mesma razão de migration Flyway ser
imutável: política antiga precisa continuar interpretável.

### 5.2 Árvore de predicados

```java
sealed interface Condition permits And, Or, Not, Comparison, AnyOf {}

record And(List<Condition> operands)          implements Condition {}
record Or(List<Condition> operands)           implements Condition {}
record Not(Condition operand)                 implements Condition {}
record Comparison(PolicyField field, Operator op, Literal value) implements Condition {}
record AnyOf(PolicyField listField, Condition each)              implements Condition {}
```

`AnyOf` é o que resolve lista, e é onde mora metade do KYB. "Algum sócio do QSA é PJ estrangeira"
é `AnyOf(company.partners, And(foreign == true, legalEntity == true))`. `NoneOf` não existe como
nó próprio: é `Not(AnyOf(...))`.

**Operadores por tipo**, validados na compilação:

| Tipo | Operadores |
|---|---|
| todos | `EQ`, `NEQ`, `IS_NULL`, `IS_NOT_NULL` |
| STRING | `IN`, `NOT_IN`, `STARTS_WITH` |
| NUMBER | `LT`, `LTE`, `GT`, `GTE`, `IN`, `NOT_IN` |
| DATE | `BEFORE`, `AFTER`, `OLDER_THAN(duração)`, `WITHIN_LAST(duração)` |
| ENUM | `IN`, `NOT_IN` |
| BOOLEAN | nenhum além dos comuns |

Literal de duração é ISO-8601 (`P6M`, `P18Y`, `PT24H`), o mesmo formato que o projeto já usa em
configuração (`barrier.assurance.attempts-window`, `barrier.identity.reuse.ttl`).

**Derivação fica no operador, não em campo calculado.** Idade não é campo: é
`profile.birthDate OLDER_THAN P18Y`. Empresa recém-aberta é
`company.openingDate WITHIN_LAST P6M`. Isso mantém a propriedade de que todo campo do catálogo
mapeia para dado gravado, sem uma segunda fonte de verdade para derivar.

A árvore é **total por construção**: sem laço, sem recursão, sem chamada de função. Profundidade
e número de nós têm teto verificado na compilação.

### 5.3 A regra do parceiro

```java
record PolicyRule(
    String code,                        // namespaced, único dentro da versão
    String name,
    Condition when,
    int score,                          // >= 0
    Severity severity,
    RiskRecommendation recommendation)  // nullable: null = apenas pontua
```

É deliberadamente a forma de um `RiskResult`. Nada no motor precisa aprender um vocabulário novo.

### 5.4 Avaliação e integração no motor

`RiskScoringService` recebe hoje `List<RiskRule>` e não sabe de onde vieram. Ganha uma segunda
fonte, resolvida por tenant:

```java
public interface CustomRuleSource {
  List<RiskRule> forContext(RiskContext context);
}
```

Cada `PolicyRule` da versão **ativa** do tenant vira um adaptador que implementa `RiskRule`:

- `code()` — o código do parceiro, em namespace próprio.
- `requires()` — união dos `ContextInput` dos campos referenciados pela árvore, derivada por
  caminhada. Não é declarada por humano, então não pode ser declarada errado.
- `evaluate(ctx)` — caminha a árvore sobre um resolvedor de campos; se a raiz é verdadeira,
  devolve `RiskResult` com score/severidade/recomendação da regra e evidência gerada pelos nós
  folha que contribuíram; senão, `RiskResult.notApplicable(code)`.

As regras custom entram no mesmo stream das de código, caem no mesmo `evaluated_json` e passam
pela mesma `ScoreAggregation`. **Tenant sem política ativa não produz regra custom nenhuma**, e o
comportamento é idêntico ao de hoje.

**Sobre o registry global.** Como as regras custom entram no mesmo stream, elas passam pelo mesmo
`activeOrLogSuppressed` e portanto pelo `RiskRuleRegistryService.isActive`. Isso é inofensivo e
não precisa de caso especial: o registry é **fail-open** por decisão registrada (regra sem linha
fica ativa, porque ele é kill switch e vigência, não allowlist), e código de regra custom nunca
terá linha lá. O efeito prático é nenhum, e de quebra sobra um kill switch de emergência por
código, caso algum dia seja preciso.

O ciclo de vida de política custom continua sendo o status da própria versão, e o kill switch
normal é arquivar a versão ativa do tenant. O registry segue sendo o cadastro das famílias do
motor, e nada escreve nele a partir deste módulo.

### 5.5 As quatro travas da compilação

É aqui que o piso fica de pé. Política que viole qualquer uma não compila, e portanto não pode
ser ativada:

1. **`score >= 0`.** É o que preserva a monotonicidade de `ScoreAggregation` (soma de scores +
   `reduce` com `RiskRecommendation::strongest`). Score negativo é o único jeito de uma regra
   custom afrouxar a decisão.
2. **Código em namespace do parceiro**: obrigatoriamente `CUSTOM_` seguido de `[A-Z0-9_]+`. O
   prefixo é o que garante, por construção, que não há colisão com família de regra do motor nem
   com código de `RegulatoryRiskRules`, e o que permite a quem lê um `evaluated_json` distinguir
   fator do motor de fator do parceiro sem consultar nada. Único dentro da versão da política.
3. **Só campos do catálogo**, com operador válido para o tipo do campo e literal do tipo certo.
4. **Teto de tamanho da árvore** (profundidade e nós).

### 5.6 O instante de referência

`OLDER_THAN` e `WITHIN_LAST` precisam de um "agora". Para que o replay de uma decisão antiga
produza o mesmo resultado, esse instante tem que ser **o da decisão**, não o relógio de parede.

`RiskContext` ganha um componente `referenceInstant`, preenchido pelo `AssessmentProcessor` a
partir do instante da avaliação e pelo `replay` a partir do instante gravado. Isso não é insumo
sujeito a lacuna: está sempre disponível.

> Nota: as regras de código de hoje que dependem de data (`NewCompanyRiskRule`) usam o relógio
> direto e têm a mesma fragilidade latente no replay. Migrá-las está **fora do escopo** deste
> spec, mas fica registrado.

## 6. Ciclo de vida e versionamento

Chave natural: **(tenant, domínio, versão)**. `domínio` vale `ONBOARDING` nesta entrega. Não é
cerimônia: é o que faz o domínio transacional (P3) entrar como valor novo, sem migrar conceito.

Estados: `DRAFT` → `ACTIVE` → `ARCHIVED`. (`SHADOW` entra em P2.)

- `version` é um inteiro monotônico **por tenant**, não por domínio. Assim `/v1/policies/{version}`
  identifica uma versão sem precisar carregar o domínio na rota.
- `catalog_version` é um inteiro próprio do catálogo de campos, incrementado a cada campo
  adicionado ou marcado obsoleto. A política grava contra qual versão foi escrita, para que uma
  política antiga continue interpretável quando o catálogo crescer.
- Versão **ativada é imutável**. Editar gera versão nova.
- Uma `ACTIVE` por (tenant, domínio) de cada vez. Ativar a nova arquiva a anterior.
- O repositório **não expõe método que reescreva regra de versão ativada**. Só criar versão,
  ativar e arquivar. A ausência do método é a defesa, mesmo raciocínio de `behavior_events`.

## 7. Persistência

**Migration V049**, duas mudanças:

- Tabela `risk_policies`: `id`, `tenant_id`, `domain`, `version`, `status`, `catalog_version`,
  `rules_json` (JSONB), `created_by`, `created_at`, `activated_by`, `activated_at`,
  `archived_at`. Índice único parcial garantindo uma `ACTIVE` por (tenant, domínio).
- Coluna `risk_scores.policy_version`, nula para tenant sem política. É o segundo eixo de versão,
  ao lado do `engine_version` que já está lá.

A árvore vai serializada em `rules_json`, no padrão de `partners_json` / `hits_json` /
`evaluated_json`. Não precisa ser consultável por dentro: é lida inteira na avaliação.

`ENGINE_VERSION` sobe para `barrier-risk-rules/1.9.0` — o motor ganhou uma fonte de regras, e uma
decisão tomada com esta versão pode conter fator que a anterior não conseguia produzir.

## 8. Trilha, replay e autoria

**`AS_DECIDED` funciona sem tocar em nada.** Ele reconfere a aritmética sobre resultado
persistido, e regra custom grava em `evaluated_json` como qualquer outra.

**Detecção de lacuna sai de graça.** O `requires()` derivado da árvore marca a regra como
`NOT_REPLAYABLE` quando um insumo não foi reconstruído, exatamente como uma regra de código.

**`CURRENT_ENGINE`** passa a reportar dois eixos: `engineVersion` e `policyVersion`, cada um
"como decidido" e "hoje". Política diferente é fonte legítima de diferença, e o ponto é que ela
fica **atribuível** em vez de aparecer como mudança de motor.

**Capacidade nova, registrada e não entregue aqui:** como a versão da política é imutável e está
guardada, dá para reexecutar *a política que de fato decidiu*. Isso é um terceiro modo de replay
e fica fora deste spec para não inchar o escopo.

**Autoria é mais simples aqui do que no motor.** A versão carrega `created_by` e `activated_by` e
é imutável, então não existe reconstrução as-of nem os quatro casos de `PolicyProvenance` que o
módulo `policy` teve que separar. "Quem definiu a política que aprovou este cliente" tem resposta
direta.

## 9. API e superfície pública

O invariante aditivo é o que **paga** o self-service. Regra que só endurece pode ser escrita pelo
próprio parceiro sem risco regulatório para o Barrier.

**Grupo `parceiro` (publicável), tenant vindo da credencial e não do caminho:**

| Rota | Ação |
|---|---|
| `POST /v1/policies` | cria versão `DRAFT` |
| `GET /v1/policies` | lista versões do tenant |
| `GET /v1/policies/{version}` | lê uma versão |
| `POST /v1/policies/{version}/activate` | ativa (arquiva a anterior) |
| `POST /v1/policies/{version}/archive` | arquiva |
| `GET /v1/policy-fields` | catálogo de campos — documentação viva |

`/v1/tenants/{id}/risk-config` **continua administrativo**, porque calibra parâmetro e pode
afrouxar. O critério fica escrito: **quem só endurece é self-service; quem calibra segue admin.**

`ApiRoutes` **não precisa mudar**, e o motivo é bom: ele é uma *denylist*, não uma allowlist. Tudo
sob `/v1/` é rota de parceiro exceto o que casa o padrão administrativo, então `/v1/policies` e
`/v1/policy-fields` já nascem protegidas pelo filtro de tenant. Foi justamente a inversão para
denylist que impediu os módulos `mesa` e `behavior` de nascerem inacessíveis de novo. Ainda assim,
confirmar por teste em vez de assumir, e não editar o padrão `ADMIN`.

O `OpenApiCoverageIntegrationTest` quebra o build se a rota nascer sem contrato, e o
`ApiRouteCoverageTest` exige que todo controller caia em exatamente um dos dois lados.

A resposta de erro de compilação tem que citar o campo, o operador e a trava violada. Contrato que
descreve mal o erro empurra o dev externo para o suporte, que foi exatamente o defeito corrigido
no `AuthenticatedTenant` publicado como query parameter.

## 10. Módulo e dependências

Módulo novo `com.barrier.riskengine.riskpolicy`, irmão dos demais. Não vai dentro de `policy`,
que já é o leaf de proveniência (`PolicyProvenance` / `RegistryPolicyState` / `ParamAuthorship`), e
misturar os dois confundiria o vocabulário.

**Inversão obrigatória.** `RiskScoringService` (módulo `risk`) precisa de `CustomRuleSource`, e a
implementação precisa de `RiskRule` / `RiskResult` / `RiskContext` (módulo `risk`). Declarar a
interface no lado da implementação fecharia `risk → riskpolicy → risk`.

Portanto: **a interface `CustomRuleSource` é declarada em `risk`** (que não sabe quem a
implementa) e implementada em `riskpolicy`. Mesmo padrão de `AssuranceRecordedListener` e
`AssessmentCompletedListener`. `sem_ciclos_entre_modulos` é quem prova.

## 11. Testes

- **Unitário**: cada operador por tipo; caminhada de `AnyOf` sobre lista vazia, de um e de vários;
  `Not(AnyOf)`; evidência gerada com `BY_VALUE` e com `OUTCOME_ONLY`.
- **Compilação**: uma falha por trava de §5.5, com a mensagem certa.
- **Invariante aditivo — o teste que é a entrega.** Para um conjunto de contextos e políticas
  compiláveis, a decisão *com* política nunca é mais fraca que a decisão *sem* ela, nem em score
  nem em recomendação. Verificável nos dois lados; é o que impede o piso de erodir por acidente
  futuro.
- **`requires()` derivado**: teste que compara os `ContextInput` derivados da árvore com os campos
  efetivamente resolvidos durante a avaliação, no espírito do `RiskRuleContextDeclarationTest`.
- **ArchUnit**: `riskpolicy` não depende de nenhum pacote `client` (regra de parceiro não sai para
  a rede), no padrão de `replay_nao_alcanca_integracao_externa`; e `sem_ciclos_entre_modulos`
  cobre a inversão.
- **Integração (Testcontainers)**: criar `DRAFT`, ativar, submeter avaliação, conferir que o fator
  custom aparece em `evaluated_json` e no `GET /v1/assessments/{id}`; conferir que
  `risk_scores.policy_version` foi gravada; conferir que tenant sem política decide igual ao
  comportamento anterior.
- **Replay (integração)**: decisão tomada com política, `AS_DECIDED` íntegro; regra custom que lê
  `COMPANY` em decisão de PJ vira `NOT_REPLAYABLE`.

## 12. Fora de escopo (deliberado)

- **Shadow mode e backtesting.** É P2, e é a trava de ativação que sustenta o pitch. Fora daqui
  para que P1 seja entregável e testável com parceiro real antes.
- **DSL em texto.** A árvore é a representação canônica; sintaxe textual é açúcar que compila para
  ela. Entra quando um parceiro pedir.
- **UI de edição.** Posicionamento B no ADR-0020. A árvore já é estruturada, então a UI é barata
  depois.
- **Terceiro modo de replay** (reexecutar a política que decidiu). Registrado em §8.
- **Migrar regras de código para o `referenceInstant`.** Registrado em §5.6.
- **Domínio transacional e agregados comportamentais.** É P3.

## 13. Decisões explícitas

- **Regra custom pode forçar `REJECT`?** Sim. É apetite do parceiro sobre cliente do parceiro, e
  o invariante aditivo garante que isso só endurece. A decisão cai no fluxo terminal que já
  existe.
- **Teto de pontuação por regra custom?** Não, além do `0..1000` por regra e do `MAX_SCORE`
  global. Um teto menor seria arbitrário, e a direção do dano já está limitada pelo invariante.
- **Regra custom passa pelo `risk_rule_registry`?** Não. O registry é o cadastro das famílias do
  motor. Kill switch de política custom é arquivar a versão ativa.
- **Onde vive a política de um tenant sem `ACTIVE`?** Em lugar nenhum: o tenant simplesmente não
  tem regra custom, e o motor se comporta como hoje.

## 14. Riscos conhecidos

- **O catálogo vira o limite real do produto.** "100% custom" é verdade dentro do catálogo, e o
  parceiro vai pedir campo que não existe. É um limite honesto e documentado, mas é o que vai
  gerar a fila de pedidos. Mitigação: `GET /v1/policy-fields` publicado desde o dia um, e cada
  campo novo é entrega pequena.
- **Política ativada sem prova de impacto.** Até P2 existir, o parceiro ativa no escuro. É o
  argumento mais forte para P2 vir logo em seguida, e vale dizer isso ao primeiro parceiro.
- **Custo de avaliação.** Árvore com teto é barata, mas N regras custom por tenant multiplicam por
  avaliação. Medir antes de abrir para tenant com base grande.
- **A hipótese comercial continua não validada.** O próprio documento de estratégia recomenda
  entrevistar ~20 empresas antes de construir. Este spec não substitui isso; ele é a forma mais
  barata de ter algo real para mostrar nessas conversas.
