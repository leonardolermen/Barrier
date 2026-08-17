# ADR-0017: Ownership de recovery — quem re-enfileira o quê

- **Status:** Aceito
- **Data:** 2026-08-15

## Contexto

O Barrier tem hoje quatro mecanismos de recuperação, cada um escrito numa entrega diferente,
cada um correto isoladamente:

| Mecanismo | Onde | O que faz |
|---|---|---|
| Lease de processamento | [`AssessmentProcessor`][proc] + [V023][v023] | avaliação reivindicada por instância que morreu volta a ser reivindicável ao expirar `claimed_at` |
| Backoff/desistência | [`Assessment.markFailedAttempt`][assess] | `attempts`/`next_attempt_at`; esgotado, o status vira `FALHA_PROCESSAMENTO` |
| Relay da outbox | [`OutboxRelay`][relay] + [V025][v025] | evento PENDING é republicado até o broker confirmar |
| Retry de entrega | [`DeliveryRetryScheduler`][retry] | entrega de webhook vencida é retentada com backoff |
| Reconciliação de entrega | [`DeliveryReconciliationJob`][recon] | relê o tópico numa janela de 6h e cria entrega para decisão que não tem uma |

**O que não existe é o documento que diz quem é dono de qual estado.** Nenhum deles declara o
que *não* faz, e nenhum aponta para o vizinho. É exatamente assim que dois mecanismos passam a
recuperar a mesma coisa: cada um olha um sintoma (linha parada, evento pendente, entrega
faltando), e a mesma avaliação pode satisfazer dois sintomas ao mesmo tempo.

O ecossistema Origem já pagou por esse aprendizado. O `bureau_pending_worker` duplicou
solicitações ao bureau abrindo ciclo novo para casos que outro mecanismo já estava conduzindo;
o [`adr-bureau-recovery-ownership.md`][origem] foi escrito **depois** do incidente, tem 29
linhas, e a parte que resolve é uma tabela de responsabilidade por estado terminada em
proibições explícitas. Ver [lições do Origem][licoes], prioridade 9 — é o item mais barato da
fila e previne uma classe de bug que o Barrier ainda não teve porque ainda não escalou.

O risco não é hipotético no nosso desenho: cada avaliação reprocessada é uma consulta paga ao
bureau (R$0,04 na BigBoost — ver [ADR-0014](0014-bureau-cpf-bigboost.md)) e um evento a mais
entregue ao parceiro. Duplicar recuperação custa dinheiro e polui a trilha de auditoria.

## Decisão

Vamos declarar **um dono por estado de falha**. Cada estado recuperável do sistema tem
exatamente um mecanismo autorizado a agir sobre ele; os demais o observam e não tocam.

### Tabela de ownership

| Estado | Dono | Mecanismo | O que o dono **não** faz |
|---|---|---|---|
| `assessment` em `EM_ANALISE` com `claimed_at` expirado | `AssessmentProcessor` | lease expira, linha volta à fila de reivindicação | não publica evento nem cria entrega; a conclusão é que grava a outbox |
| `assessment` em `EM_ANALISE` com `next_attempt_at` futuro | `AssessmentProcessor` | backoff por `attempts` | não é reivindicável por ninguém antes do prazo — nem por operação manual sem zerar o backoff |
| `assessment` em `FALHA_PROCESSAMENTO` | **ninguém** (terminal automático) | desistência deliberada após esgotar `attempts` | nenhum job re-enfileira; sair daí é ato humano explícito, e o parceiro já sabe que falhou |
| `outbox_event` PENDING (nunca publicado, ou publicado sem marcação) | `OutboxRelay` | reivindica por lease, publica, marca SENT | não recria evento nem reprocessa a avaliação de origem |
| decisão publicada sem `delivery` correspondente | `DeliveryReconciliationJob` | relê o tópico (janela `PT6H`, consumidor avulso sem commit) e cria a entrega faltante | **não reprocessa avaliação**; só cria entrega, e a criação é idempotente por `eventId` |
| `delivery` falhada com retry pendente | `DeliveryRetryScheduler` | `retryDue()` com backoff | não relê o tópico e não cria entrega nova |
| evento na `<tópico>.DLT` | `DeliveryReconciliationJob` | é o único caminho de volta — o listener não consome a DLT | não republica no tópico original |
| entrega perdida de `barrier.subject.risk_level_changed` | **ninguém** (sem reconciliação, deliberado) | — | o reconciliador relê só `barrier.assessment.completed`; ver nota abaixo |
| bureau indisponível (`IdentityStatus.UNAVAILABLE`) | **ninguém** (não é falha recuperável) | a avaliação **conclui** em `EM_REVISAO` via `IdentityRiskRule` | não há re-tentativa automática: a decisão já foi tomada, e é escalar para humano |

### Proibições explícitas

Escritas no tom do ADR do Origem, para serem citáveis em code review:

- O `DeliveryReconciliationJob` **não** reprocessa avaliação; ele só cria entrega faltante.
- O `DeliveryRetryScheduler` **não** relê o tópico; ele só age sobre `delivery` que já existe.
- O `OutboxRelay` **não** reabre a avaliação de origem; evento que falha ao publicar continua
  PENDING e é problema do relay, não do processor.
- Nada re-enfileira `FALHA_PROCESSAMENTO` automaticamente.
- Nada re-tenta `UNAVAILABLE` de bureau em background — re-tentar seria decidir de novo o que
  já foi decidido, com uma consulta paga a mais e um segundo evento para o mesmo assessment.

### Nota: por que mudança de nível não é reconciliada

O `DeliveryReconciliationJob` relê um tópico só (constante `TOPIC` na classe). Quando
`barrier.subject.risk_level_changed` entrou (fila-origem F4), a escolha foi **não** generalizar o
job, e ela é consciente: o evento de nível é uma *notificação sobre um estado consultável*, não o
único registro de um fato. Se a entrega se perder, o risco corrente continua em
`subject_risk_state` e o parceiro o obtém em `GET /v1/subjects/{document}/risk-state`; a próxima
mudança de nível reemite. Já uma decisão de KYC perdida não tem esse recurso — é o desfecho, e
por isso ela tem reconciliação.

O que **não** era aceitável era deixar isso implícito: um evento sem dono de recuperação e sem
linha nesta tabela é indistinguível de um esquecimento. Se um dia o parceiro passar a depender do
evento de nível como registro (e não como aviso), a decisão se inverte e o job vira multi-tópico.

### O ponto que exigiu decisão nova

Os sete primeiros estados são documentação do que o código já faz. O oitavo não era: bureau
indisponível é o único estado onde havia ambiguidade real — "ninguém re-tenta" podia ser
esquecimento em vez de escolha.

É escolha. A cadeia de bureaus só chega a `UNAVAILABLE` quando **todos** os providers
falharam, e nesse ponto `IdentityRiskRule` força REVIEW em vez de deixar a indisponibilidade
virar aprovação automática — o assessment **conclui**, com desfecho, evento e entrega. Não há
estado pendurado para recuperar. Um retry em background criaria um: a avaliação já concluída
em `EM_REVISAO` ganharia uma segunda decisão, possivelmente divergente, depois de o parceiro
já ter recebido a primeira pelo webhook.

Se o apetite mudar — re-tentar cedo antes de escalar para a mesa —, o lugar é **dentro** do
processamento, antes de concluir (o disjuntor por bureau já é o mecanismo que sabe quando o
provider voltou), nunca um job novo varrendo avaliações concluídas.

## Alternativas consideradas

- **Um job único de recuperação, dono de tudo.** Centralizaria a decisão, mas atravessaria a
  fronteira dos dois deployables: `assessments` vive no schema da risk-engine e `deliveries`
  no schema `webhook` — exatamente o acoplamento entre schemas que o
  `DeliveryReconciliationJob` evita ao usar o tópico como fonte de verdade. Trocaria uma classe
  de bug por outra pior.
- **Não escrever nada e confiar no Javadoc de cada mecanismo.** É o estado atual. Cada Javadoc
  descreve bem o que sua classe faz; nenhum descreve o que ela deixa para o vizinho, e é no
  vazio entre eles que a duplicação nasce. Foi assim que o Origem duplicou consulta ao bureau.
- **Deduplicação defensiva em vez de ownership** (deixar todos recuperarem e barrar o
  duplicado). O `eventId` já protege a entrega, mas não protegeria a consulta ao bureau — que é
  onde o dinheiro está. Dedup depois do gasto não evita o gasto.

## Consequências

- **Positivas:** cada mecanismo novo passa a ter onde se encaixar (a tabela é o teste: se um
  estado já tem dono, a proposta está duplicando); code review ganha vocabulário citável; a
  decisão sobre `UNAVAILABLE` deixa de ser folclore.
- **Negativas / custos:** a tabela é documento, não código — nada a mantém sincronizada com a
  implementação além de disciplina. Mitigação: toda entrada de fila que mexa em recuperação
  (F3 em diante, ver [fila-origem][fila]) atualiza esta tabela como parte do "pronto".
- **Riscos e mitigações:** o `DeliveryReconciliationJob` é limitado pela retenção do Kafka —
  evento mais velho que a retenção não é recuperável por ninguém, e a tabela não muda isso.
  Fica registrado como limite conhecido, não como estado sem dono.

[proc]: ../../services/risk-engine/src/main/java/com/barrier/riskengine/assessment/service/AssessmentProcessor.java
[assess]: ../../services/risk-engine/src/main/java/com/barrier/riskengine/assessment/domain/assessment/Assessment.java
[relay]: ../../commons/src/main/java/com/barrier/commons/outbox/OutboxRelay.java
[retry]: ../../services/webhook-api/src/main/java/com/barrier/webhook/service/DeliveryRetryScheduler.java
[recon]: ../../services/webhook-api/src/main/java/com/barrier/webhook/service/DeliveryReconciliationJob.java
[v023]: ../../services/risk-engine/src/main/resources/db/migration/V023__assessment_processing_control.sql
[v025]: ../../services/risk-engine/src/main/resources/db/migration/V025__outbox_claim.sql
[origem]: ../../../Backend/bmp-origem-back/Docs/Cadastro%20Unico/adr-bureau-recovery-ownership.md
[licoes]: ../implementation/licoes-do-origem.md
[fila]: ../implementation/fila-origem.md
