# Escala horizontal — 5 réplicas em Kubernetes atrás de load balancer

> **Para quem executa:** passos com checkbox são a unidade de trabalho. TDD onde couber: teste
> que falha, rodar e ver falhar, implementação mínima, rodar e ver passar, commit.

**Origem:** item 1 (P0) do [plano-auditoria-2026-08-18.md](plano-auditoria-2026-08-18.md), aberto
em profundidade depois da pergunta *"isso roda em 5 pods com autoscaler e load balancer?"*.

**Objetivo:** sair de "acreditamos que escala horizontalmente" para "sabemos, e o build prova".

**Diagnóstico de partida:** o mecanismo difícil já está certo e nunca foi exercitado. As quatro
filas de trabalho usam `FOR UPDATE SKIP LOCKED` + lease (`AssessmentProcessor`, `OutboxRelay`,
`AssuranceResultPoller`, `DeliveryRetryScheduler`), a API é stateless e o Flyway pega advisory
lock na subida. Em cima disso: **nenhum container, nenhum lock nos jobs singleton, tópico com
uma partição, e `ConcurrentClaimIntegrationTest` roda com uma instância só.** A auditoria deu
3,0 em Escalabilidade por isso — não por desenho ruim, por hipótese não verificada.

---

## O que já é seguro com N réplicas (não mexer)

| Componente | Por que é seguro |
|---|---|
| `AssessmentProcessor.process` | `claim` com `FOR UPDATE SKIP LOCKED` + lease + `@Version` — réplicas pegam conjuntos disjuntos |
| `OutboxRelay` | claim com lease (V025); I/O do broker fora do lock |
| `AssuranceResultPoller` | `claimPendingBiometric` (V038), mesmo padrão |
| `DeliveryRetryScheduler` | `claimDue` com `LockOptions.SKIP_LOCKED` |
| Autenticação / API | stateless (Bearer + `AuthenticatedTenant` por requisição) — LB round-robin, sem sticky session |
| Flyway na subida | advisory lock próprio; 5 pods subindo juntos não colidem |
| `PipelineHealthMetrics` | amostragem **deve** ser por pod; agregação é do Prometheus |

Estes são o ativo. Todo o resto abaixo constrói **sobre** eles.

---

## Task 1 — Container e ciclo de vida no Kubernetes

Sem isto nada mais é verificável, e é o mesmo trabalho do Dockerfile já pedido no P0.

- [x] `Dockerfile` multi-stage por serviço (`risk-engine`, `webhook-api`): build com Maven,
      runtime em JRE 25 slim, usuário não-root, `-XX:MaxRAMPercentage` em vez de `-Xmx` fixo
      (heap fixo ignora o limit do pod e vira OOMKill).
- [x] **Graceful shutdown** — hoje não existe: `server.shutdown: graceful` e
      `spring.lifecycle.timeout-per-shutdown-phase: 30s`. Sem isso, todo scale-down do autoscaler
      mata requisição em voo **e** abandona lease de lote no meio, que só volta a ser
      reivindicável quando o lease expira.
- [x] **Probes separadas, e a distinção importa:** `readinessProbe` →
      `/actuator/health/readiness`, `livenessProbe` → `/actuator/health/liveness`. Usar `/health`
      cheio na liveness é a armadilha clássica: o `WatchlistHealthIndicator` derruba o health
      quando a cobertura de lista cai — com isso na liveness, **o Kubernetes reinicia os 5 pods em
      loop** por um problema que reiniciar não resolve. Habilitar
      `management.endpoint.health.probes.enabled` e mapear os indicadores certos para cada grupo.
- [x] Sizing explícito: Hikari `maximum-pool-size` × 5 pods **não pode passar** do `max_connections`
      do Postgres. Hoje não há sizing nenhum — o default de 10 por pod são 50 conexões, mais o
      `webhook-api`. Definir e documentar a conta.
- [x] `terminationGracePeriodSeconds` do Deployment ≥ o timeout de shutdown.

*Pronto quando:* `docker compose up` sobe os dois serviços a partir das imagens, e um `SIGTERM`
durante requisição em voo devolve resposta em vez de conexão cortada.

---

## Task 2 — Kafka deixa de ser teto rígido

- [x] Declarar os tópicos com `NewTopic` (partições explícitas, ≥ número de réplicas alvo). Hoje
      **não há nenhum** — os tópicos nascem com o default do broker (tipicamente **1 partição**),
      e com uma partição **um pod consome e os outros quatro ficam ociosos**. A webhook-api não
      escala hoje, independente de quantos pods subam.
- [x] `concurrency` explícita no `@KafkaListener` (hoje ausente = 1 thread por pod).
- [ ] Documentar a conta no [event-catalog.md](../architecture/event-catalog.md): partições ≥
      réplicas, e o efeito na ordem — a ordem é garantida **por chave de partição**
      (`assessmentId`, `subjectId`), nunca global. Aumentar partição depois redistribui chaves; o
      catálogo é o lugar onde isso fica registrado antes de alguém descobrir em produção.

*Pronto quando:* com 5 réplicas, todas as partições têm consumidor atribuído e o lag drena em
paralelo — verificável no cluster local da Task 5.

---

## Task 3 — Jobs singleton param de rodar 5 vezes ⚠️

Não existe ShedLock nem advisory lock no repositório. Cinco `@Scheduled` que deveriam rodar uma
vez globalmente rodam em cada pod:

| Job | Efeito com 5 pods |
|---|---|
| `WatchlistImporter` (03:00) | 5 downloads do ZIP OFAC/CGU/ONU **e 5 `replaceSource`** — o `WatchlistDelta` alimenta o `RescreeningService`, então uma importação pode virar **5 avalanches de rescreening**, cada avaliação com consulta paga |
| `PeriodicReassessmentJob` (03:30) | `max-per-run=200` vira **1000/noite** — o teto que existe justamente para controlar custo deixa de valer |
| `AlertEvaluator` | `lastNotified` é `HashMap` em memória (linha 47): dedup por pod → 5× alertas. Salvo por acidente hoje, porque o `dedup_key` do PagerDuty é o código do alerta e ele deduplica do lado de lá; **qualquer canal novo (Slack) fanaria out 5×** |
| `DeliveryReconciliationJob` | 5 pods relendo o mesmo tópico com `assign` |
| `IdempotencyService` purge | inofensivo (delete idempotente) — não precisa de lock |

- [x] Lock distribuído para os quatro primeiros. **Implementado como lease em tabela** (`SingletonJobLock`, V045), **não** advisory lock — ver a revisão de decisão abaixo. Racional original: advisory lock seria preferível a
      ShedLock: já temos Postgres, é uma dependência a menos, e `pg_try_advisory_lock` solta
      sozinho ao fim da sessão — job que morre com o pod não deixa lock preso, que é o modo de
      falha que mais dói às 3 da manhã.
- [x] ⚠️ **A armadilha, e ela precisa entrar na MESMA mudança.** `WatchlistImportStatus` é
      `ConcurrentHashMap` **em memória, por pod** — e isso é deliberado (`CLAUDE.md`: *"o que
      importa é se esta instância tem cobertura utilizável"*). Funciona hoje porque o
      `WatchlistImporter` é `ApplicationRunner` **e** `@Scheduled`, então **todo pod importa**.
      Pôr lock no importer sem mais nada deixa **4 pods com status vazio** →
      `ScreeningCoverageRiskRule` força REVIEW → **100% das avaliações de 4 dos 5 pods vão para
      revisão manual**. A correção ingênua produz incidente pior que o problema.
      **Portanto:** mover a cobertura de importação para o banco (tabela de status por fonte, lida
      por qualquer pod) **junto** com o lock, nunca depois. O `WatchlistHealthIndicator` e o
      `ScreeningCoverageRiskRule` passam a ler de lá.
- [ ] Decisão explícita sobre `CircuitBreakerRegistry`: estado por instância é **defensável**
      (isolamento de falha, sem coordenação no caminho quente), mas com 5 pods são 5×5 falhas para
      abrir em todos e 5 sondagens independentes contra um provedor já caído. Registrar a escolha
      — manter local ou compartilhar — com o motivo, em vez de herdá-la por omissão.

*Pronto quando:* teste de integração com duas instâncias concorrentes prova que o job roda **uma
vez**; e teste provando que um pod que **não** importou ainda enxerga a cobertura e **não** força
REVIEW.

---

## Task 4 — Autoscaler pelo sinal certo

- [x] **HPA por CPU está errado aqui, e a evidência é do próprio projeto.** O pipeline é
      I/O-bound em bureau: a CPU fica **baixa exatamente quando a fila está afogando**. Foi assim
      que 69.809 avaliações ficaram presas em `EM_ANALISE` sem erro, sem latência ruim e sem
      alerta (teste de carga do ADR-0015). Um HPA por CPU nunca dispararia nesse cenário — mediria
      saúde num sistema parado.
- [x] Escalar por **profundidade de fila**: `oldestPendingCreatedAt()` e as métricas do
      `PipelineHealthMetrics` já existem. Isso significa **KEDA** (`ScaledObject` sobre métrica
      Prometheus), não HPA padrão.
- [x] Teto de réplicas amarrado ao pool de conexões (Task 1) e à cota de bureau: autoscaler sem
      teto transforma pico de tráfego em pico de **fatura**, e o custo por avaliação é real.
      Cruza com o item de cota por tenant do P1 — sem cota, escalar é amplificar o abuso.

*Pronto quando:* backlog artificial faz o cluster local subir réplica, e drenar faz descer.

---

## Task 5 — Provar localmente, e no CI com o mesmo tool

- [x] **kind** (`kind create cluster`) — binário único, roda dentro do Docker, e é o **mesmo tool
      no GitHub Actions**. Preferido ao Kubernetes do Docker Desktop, que depende de um toggle de
      GUI e não é reproduzível no CI. (`kubectl` já está instalado nesta máquina; `kind` não —
      instalar. O Docker Desktop está instalado mas com o daemon parado.)
- [x] Manifests em `deploy/k8s/`: Deployment (5 réplicas), Service, ConfigMap/Secret, e o
      `ScaledObject` da Task 4. Postgres e Kafka no cluster para o teste local.
- [x] **O teste que fecha a frente:** subir 5 réplicas, injetar N avaliações, e provar que
      **cada uma foi processada exatamente uma vez** — sem duplicata (lease funciona entre pods) e
      sem sobra (nenhuma ficou órfã). Hoje `ConcurrentClaimIntegrationTest` prova disjunção com
      **duas threads numa instância**; o que falta é entre **processos** — que é onde
      `@Version`, lease e visibilidade transacional podem divergir da intuição.
- [x] Rolling update sob carga: nenhuma requisição perdida (valida a Task 1).
- [ ] Matar um pod no meio de um lote: o lease expira e outro pod reivindica — nada fica preso.

*Pronto quando:* o job roda no CI e falha o build se a disjunção quebrar.

---

## Ordem e dependências

```
Task 1 (container + ciclo de vida)  ← bloqueia todo o resto
   ├── Task 2 (partições)           independente das demais
   ├── Task 3 (locks + cobertura)   ⚠️ as duas metades juntas, nunca separadas
   └── Task 5 (kind + prova)        precisa de 1; valida 2, 3 e 4
        └── Task 4 (KEDA)           precisa do cluster da 5 para ser exercitada
```

**Sequência recomendada:** 1 → 2 → 5 (cluster de pé, mesmo que provando pouco) → 3 → 4. Subir o
cluster antes da Task 3 é deliberado: a armadilha da cobertura de watchlist é o tipo de coisa que
só aparece com pods de verdade, e descobri-la com o cluster já montado é barato.

---

## Fora de escopo, registrado para não voltar como surpresa

- **Multi-região / DR com RPO/RTO** — outra ordem de problema; é item de enterprise-readiness.
- **Postgres como gargalo único.** Todas as filas e leases passam por ele. A 5 pods não é
  problema; a 50 é, e a resposta seria réplica de leitura para as consultas de análise antes de
  qualquer sharding. **Não é possível afirmar onde vira gargalo sem benchmark** — o benchmark
  correto é carga sustentada com bureau real medindo conexões ativas, contenção de lock e p99 do
  `claim`.
- **Cota por tenant** — está no P1 do plano de auditoria e é pré-requisito do autoscaler ter
  teto útil, mas é frente própria.

---

## Estado da execução (2026-08-19)

**Tasks 1 e 2 fechadas e verificadas em cluster real.** 5 réplicas de cada serviço rodando em
`kind`, todas `1/1 Running`.

### O bug que só o cluster encontrou

A primeira versão do `KafkaTopicsConfig` expunha um bean **`List<NewTopic>`**. O teste unitário
passava — a lista tinha os três nomes e as partições certas — e **nenhum tópico era criado**: o
`KafkaAdmin` varre o contexto por beans do tipo `NewTopic` ou `KafkaAdmin.NewTopics`, e um
`List<NewTopic>` não é nem um nem outro. O bean era ignorado em silêncio, o broker voltava a
auto-criar com 1 partição, e o teto de escala continuava lá — agora **atrás de uma classe de
configuração que aparentava tê-lo resolvido**, que é estritamente pior que não ter a classe.

O unitário provava aritmética sobre um valor de retorno; o que precisava de prova era o *wiring*.
`KafkaTopicCreationIntegrationTest` sobe o contexto contra um Kafka real e consulta o **broker**
via `AdminClient` — falhou com `UnknownTopicOrPartitionException` antes da correção, passa depois.

Vale registrar o padrão, porque é o mesmo do achado de `/v1/mesa` estar fora do filtro de auth:
**nos dois casos existia teste, e o teste media a coisa errada.** Um verificava a lista de rotas
protegidas sem verificar se a rota estava na lista; o outro verificava a lista de tópicos sem
verificar se alguém a lia. Teste de unidade não substitui teste de fronteira.

### Verificado ao vivo

| O quê | Resultado |
|---|---|
| Imagens | Constroem; `risk-engine` 642MB, `webhook-api` 625MB |
| 5+5 pods no kind | `1/1 Running` |
| Tópicos no broker | 3 tópicos × **6 partições** (antes: inexistentes) |
| **Distribuição de partição** | **5 consumer-ids distintos, todas as 6 partições atribuídas** — o teto de "1 pod consome, 4 ociosos" acabou |
| Rolling update | `kubectl rollout restart` → `successfully rolled out`, sem indisponibilidade |
| Suíte | 619 risk-engine + 53 webhook-api, verde **com Docker** (os 14 de integração incluídos) |

### Observação para a Task 3

Na primeira subida os 10 pods entraram em `CrashLoopBackOff` — Flyway não conectava porque o
Postgres ainda não aceitava conexão. Convergiu sozinho em ~2 reinícios, que é o comportamento
correto do Kubernetes (reconciliação, não ordenação). **Não** adicionar init container por causa
disso: dependência declarada esconde o fato de que a aplicação precisa tolerar banco
temporariamente ausente, e ela precisa mesmo — failover de Postgres em produção produz o mesmo
cenário. O que vale revisar é o `startupProbe`, que já cobre a subida lenta.

### Ambiente (para quem retomar)

- Docker Desktop travava em `initializing Inference manager` por **três sockets órfãos** em
  `%LOCALAPPDATA%\Docker\run`, indeletáveis por `rm`/`Remove-Item`/`del` long-path/`wsl --shutdown`.
  Solução: **renomear o diretório** (`run` → `run-orfao-<timestamp>`); o Docker recria limpo.
  `EnableDockerAI: false` ficou no `settings-store.json` (backup em `.bak-barrier`) mas **não era
  a causa** — pode voltar a `true`.
- `kind` v0.32.0 instalado via `winget`, em
  `%LOCALAPPDATA%\Microsoft\WinGet\Packages\Kubernetes.kind_*\kind.exe`.
- No Git Bash, `kubectl exec` precisa de `MSYS_NO_PATHCONV=1`, senão o caminho dentro do
  container vira `C:/Program Files/Git/...`.

### Próximo

Task 3 (lock distribuído + cobertura de watchlist no banco), com o cluster de pé — a armadilha
dos 4 pods sem cobertura é observável ali, e era para ser corrigida com pods na frente.

---

## Task 3 — executada (2026-08-19)

`SingletonJobLock` (V045) + cobertura compartilhada (V046), na mesma entrega, como o plano exigia.
Lock aplicado em `WatchlistImporter.scheduledRefresh`, `PeriodicReassessmentJob.run` e
`AlertEvaluator.evaluate`. 8 testes de integração novos, todos contra Postgres real.

### Decisão revista: lease em tabela, não advisory lock

O plano dizia `pg_try_advisory_lock`. Ao implementar, dois problemas apareceram:

- o advisory lock é ligado à **sessão** — com pool de conexões, seria preciso fixar a conexão do
  Hikari durante o job inteiro para conseguir soltá-lo;
- a variante `_xact_` só solta no fim da transação, o que manteria uma transação aberta pelos
  **minutos de download** de uma importação — idle-in-transaction segurando vacuum.

O lease em tabela é o mesmo padrão que a outbox (V025) e o claim de avaliações já usam aqui, e tem
a propriedade que importa às 3 da manhã: **pod que morre no meio não deixa lock preso**.

### O que o teste encontrou e o plano não previa

A primeira versão tinha **uma** duração de lease e liberava ao terminar. O teste de exclusividade
falhou — e estava certo em falhar: chamadas sequenciais devem mesmo rodar (é a próxima janela), o
cenário real é **concorrente**. Corrigindo o teste, apareceu o buraco que a versão de uma duração
só tinha:

> Job de 2 minutos libera o lock às 03:02. A réplica cujo cron disparou atrasado — clock skew,
> scheduler ocupado, pod que subiu depois — reivindica às 03:04 e **reexecuta a mesma janela**.
> 400 reavaliações pagas onde o `max-per-run` diz 200: **o teto continua escrito no código e
> violado na prática**.

Daí as duas durações do padrão ShedLock:

| | Significado | Diário (import, re-KYC) | Frequente (alertas) |
|---|---|---|---|
| `lockAtMostFor` | teto; quando volta a ser reivindicável se o pod morrer | 2h | 10min |
| `lockAtLeastFor` | piso; não libera antes disto, mesmo terminando | 1h | **0** |

O piso zero nos alertas é deliberado e oposto: ali reexecutar cedo é barato e **pular um ciclo é o
dano** — o avaliador existe porque a fila afogou em silêncio uma vez.

### A cobertura era um bug que já existia

Investigando a armadilha, o diagnóstico do plano ficou mais forte do que estava escrito. O
racional original do `WatchlistImportStatus` — *"o que interessa é se esta instância tem cobertura
utilizável"* — **não se sustenta**: `replaceSource` grava em `watchlist_entries`, que é
**compartilhada**. A lista sempre foi global; só a medição era local.

Ou seja, mesmo **sem lock nenhum**, hoje: 5 réplicas, o download falha em uma (blip de rede), e
aquela réplica se dá por descoberta e força REVIEW em tudo que atender — com a tabela
integralmente populada pelas outras quatro. Um quinto do tráfego em revisão manual por erro de
**medição**, não de dado.

`WatchlistCoverageSharedIntegrationTest` prova as duas coisas com **duas instâncias distintas** de
`WatchlistImportStatus` sobre o mesmo banco — o que uma instância única esconderia.

### Efeito colateral aceito

`WatchlistImporterTest` era unitário e construía o status de verdade. Com o status virando tabela,
as asserções de cobertura viraram dependência de banco. Separei: o unitário passou a **mockar** o
status e verificar interação (é o comportamento do *importador* que ele cobre), e a semântica de
cobertura foi para o teste de integração. É a fronteira certa — só não era visível antes porque o
status era um objeto de memória.

### O terceiro bug, encontrado medindo no cluster

Com o lock ligado, medi no cluster de 5 réplicas: **17 execuções do avaliador em 180s**, contra 60
que haveria sem lock. O lock funcionava — e a medição revelou outra coisa: a **liderança
rotaciona**. Com piso zero, o lease é solto ao fim de cada ciclo e o pod seguinte pode ganhar.

O dedup de alerta (`lastNotified`) era um `HashMap` **de instância**. Com liderança rotativa ele
não dedupava nada: pod A notifica, pod B ganha o ciclo seguinte com o mapa vazio e notifica o
mesmo código. **Eu tinha escrito no Javadoc que o dedup "agora é de quem de fato avalia" — estava
errado**, e só apareceu porque medi em vez de assumir.

É a **terceira ocorrência do mesmo padrão** nesta frente (tópicos, cobertura, dedup): estado que é
do *cluster* guardado na memória de *uma instância*, com comentário explicando por que estava
certo.

A correção não precisou de tabela nova: "não repetir este alerta antes de `repeat-interval`" **é**
um lease por código com piso igual ao intervalo. `dispatch` passou a usar o próprio
`SingletonJobLock` com a chave `alert:<código>`. A semântica já existia; faltava perceber que era
a mesma.

E não havia **nenhum** teste do `AlertEvaluator` — só das regras. Por isso passou.
`AlertDedupSharedIntegrationTest` cobre agora, com duas instâncias sobre o mesmo banco.

### Aberto nesta task

- `DeliveryReconciliationJob` (webhook-api) ainda sem lock: o `SingletonJobLock` vive na
  risk-engine e o outro deployable não o enxerga. Mover para o `commons` arrastaria
  `JdbcTemplate` para lá — mesma discussão do `AdminApiKeyFilter`. Decidir antes de copiar.
- `CircuitBreakerRegistry`: decisão explícita ainda pendente.

---

## Tasks 4 e 5, e o `SingletonJobLock` movido para o `commons` (2026-08-19)

### A objeção contra mover para o `commons` estava errada

Registrei que mover arrastaria `JdbcTemplate` para o `commons`, citando o precedente do
`AdminApiKeyFilter`. **Os dois pontos estavam errados**, e a verificação leva um minuto:

- `commons/pom.xml` já declara `spring-boot-starter-data-jpa`, que traz `spring-jdbc` e
  `JdbcTemplate` transitivamente. Não há dependência nova.
- `commons` tem **zero** dependência web — e *esse* era o motivo real de o `AdminApiKeyFilter`
  ficar fora. O precedente é sobre `jakarta.servlet`, não sobre acesso a banco. Citá-lo aqui foi
  analogia solta, não argumento.

`OutboxRepository` já vive lá e já toca o banco. O lease tem a mesma forma. Movido para
`com.barrier.commons.jobs`.

**Mas mover para o `commons` não faz a classe "aparecer" nos dois serviços**, e isso só ficou
visível ao rodar: a `RiskEngineApplication` está em `com.barrier` e escaneia tudo abaixo, então
pega o bean sozinha; a `WebhookApplication` está em `com.barrier.webhook` e escaneia **só** esse
pacote — deliberadamente, para não puxar os beans de outbox. Sem intervenção, a Webhook API
subiria sem o lock. Verificado nos dois sentidos: sem o `JobLockConfig` o contexto falha com
`No qualifying bean of type 'com.barrier.commons.jobs.SingletonJobLock'`; com ele, 53 testes
verdes e **nenhum** conflito de bean duplicado.

Resolvido com `` explícito (`webhook.config.JobLockConfig`) em vez de ampliar o scan: preserva
a propriedade de que este serviço escolhe item a item o que consome da biblioteca compartilhada, e
deixa a escolha legível num arquivo em vez de deduzível de um padrão de scan.

**A tabela é duplicada de propósito** (V045 na risk-engine, V006 na webhook-api): cada deployable
é dono do seu schema, e uma tabela compartilhada criaria acoplamento de banco onde hoje só existe
acoplamento por evento. O *código* do lease é único; o escopo do lock é por serviço, que é o
correto — as réplicas da webhook-api coordenam entre si, não com as da risk-engine.

`DeliveryReconciliationJob` agora tem lock: sem ele, 5 réplicas abriam 5 consumidores avulsos
varrendo a mesma janela de 6 horas do tópico ao mesmo tempo. Não gerava duplicata (a entrega é
idempotente por `eventId`), mas multiplicava por cinco o trabalho do mecanismo que existe
justamente para funcionar quando o resto já falhou.

### Task 4 — autoscaling pelo sinal certo

`deploy/k8s/autoscaling.yaml`, **não aplicado por padrão** (exige KEDA + Prometheus, que o cluster
local não tem). O valor está na escolha do sinal:

| Serviço | Gatilho | Teto | Por quê |
|---|---|---|---|
| risk-engine | `pending_count` **e** `pending_oldest_seconds` | 10 | Contagem alta pode ser pico absorvido; **idade** alta significa que não está drenando — era isso que teria gritado no ADR-0015 |
| webhook-api | lag do consumer group | **6** | = número de partições. Réplica além disso fica sem atribuição e não consome nada |

Duas decisões que não são óbvias: **`minReplicaCount` não é 0** — escalar a zero mataria os
`@Scheduled`, e "sem fila agora" não é "nada a fazer"; watchlist que não atualizou porque ninguém
estava vivo às 03:00 é controle regulatório que não rodou. E o **teto é freio de custo**: cada
réplica drena a fila mais rápido, e cada avaliação é uma consulta paga — enquanto a cota por
tenant (P1) não existir, este teto é o único freio que há.

### Task 5 — o teste que fecha a frente

`deploy/verify-disjuncao.sh`: submete N avaliações pelo Service e verifica **três** coisas —
nenhuma processada duas vezes (duplicata = lease falhou), nenhuma presa em `EM_ANALISE` (órfã = o
modo de falha do ADR-0015) e **a distribuição por pod**. A terceira existe porque sem ela uma
réplica fazendo todo o trabalho passaria no teste sem provar nada sobre concorrência.

⚠️ **Escrito, ainda não executado ponta a ponta.** O que já foi verificado ao vivo: 5+5 pods de
pé, partições distribuídas entre as 5 réplicas, rolling update sem indisponibilidade, e o lease
rotacionando com ~1 execução por ciclo em vez de 5.

### Ainda aberto

- Rodar o `verify-disjuncao.sh` e registrar o resultado.
- Matar um pod no meio de um lote e confirmar que o lease vencido libera o trabalho.
- `CircuitBreakerRegistry`: decisão explícita (local vs compartilhado) segue pendente.
- KEDA/Prometheus no cluster local, para exercitar a Task 4 de fato.
