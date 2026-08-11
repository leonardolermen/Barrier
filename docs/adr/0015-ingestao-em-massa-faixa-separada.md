# ADR-0015: Ingestão em massa como faixa separada, com cota por tenant

- **Status:** Proposto
- **Data:** 2026-08-10

## Contexto

Teste de carga em `main` (k6, ramp 10→150 VUs, ~5 min, perfil default com bureau simulado)
mediu o descompasso entre as duas metades do sistema:

| | Medido |
|---|---|
| Ingestão HTTP (`POST /v1/assessments`) | **292 req/s**, 0% de erro, p95 762ms, p99 1,15s |
| Processamento (`AssessmentProcessor`) | **~12,5/s** ocioso; ~2,7/s competindo com o HTTP |
| Resultado | 70.558 submetidas, ~800 concluídas, **69.809 presas em `EM_ANALISE`** |

O `POST` aceita duas ordens de grandeza mais do que o pipeline conclui. E o número real de
produção é pior: no teste o `FakeCpfBureauProvider` não faz I/O de rede; com bureau real
(~300ms por consulta) o laço sequencial de [`AssessmentProcessor.process()`][proc] fica em
**~3/s**, coerente com o "~1–3 TPS/instância" já registrado no plano de remediação.

Isso não é só lentidão. Três consequências qualitativamente distintas:

**1. O `202` deixa de significar o que promete.** Não há erro, não há latência ruim, não há
alerta — só uma fila invisível crescendo. Nada no sistema mede a idade do item mais antigo da
fila, então "pico absorvido" e "afogando" são indistinguíveis de fora.

**2. Não existe isolamento entre tenants.** O [`SELECT_CLAIMABLE`][claim] ordena por
`created_at` **global**, sem `tenant_id`: sem cota, sem fatia justa, sem prioridade. Um cliente
que tomba uma base de 500 mil às 9h coloca a avaliação em tempo real de qualquer outro cliente
atrás de 500 mil registros — dois dias de espera, sem que ele tenha violado nenhuma regra da
API. Um cliente derruba o SLA de todos os outros usando o produto exatamente como documentado.

**3. O custo é o limite real, não a thread.** O bureau de produção é a BigBoost (a BrasilAPI é
da fase de teste — ver [ADR-0014](0014-bureau-cpf-bigboost.md)), a R$0,04/consulta na faixa
inicial. Um tombamento é um evento financeiro antes de ser técnico:

| Base | Custo em consultas |
|---|---|
| 100 mil | R$ 4.000 |
| 500 mil | R$ 20.000 |
| 1 milhão | R$ 40.000 |
| 5 milhões | R$ 200.000 |

Hoje um cliente com um laço sobre o `POST` gera dezenas de milhares de reais de custo sem que
ninguém aprove nada, e a descoberta acontece na fatura. **Acelerar o processamento sem antes
controlar a entrada apenas queima o mesmo dinheiro mais rápido** — e é por isso que a ordem
das decisões abaixo importa mais que o conteúdo delas.

## Decisão

Tratar **ingestão em massa e tempo real como dois regimes distintos**, nesta ordem:

**1. Cota e aceite explícito por tenant (primeiro).** Lote grande passa a ser operação
negociada, não descoberta: endpoint de importação próprio, com cota configurada por tenant,
custo estimado antes da execução e prazo declarado ("sua base fica pronta em ~40h"). Sem cota
configurada, o volume excedente é recusado — **fail-closed**, no padrão que o plano de
remediação exige.

**2. Faixa (`lane`) na fila.** Coluna de classe em `assessments` (`REALTIME` | `BULK`) entrando
no `ORDER BY` do claim: o lote só consome capacidade ociosa e nunca passa à frente do tempo
real. Entre tenants na mesma faixa, round-robin em vez de `created_at` puro, para que dois
backfills simultâneos também não se matem.

**3. Paralelismo fixo e configurável, não dinâmico.** `barrier.assessment.workers` (default
conservador, 4), com `spring.datasource.hikari.maximum-pool-size` dimensionado explicitamente
junto — hoje não há config de Hikari em nenhum `application.yml`, então vale o default de 10,
compartilhado com o Tomcat. Passar de ~8 workers sem mexer no pool apenas troca espera por
bureau por espera por conexão, roubando conexão das requisições dos clientes.

O `SKIP LOCKED` + `claimed_at` com lease de `PT5M` já em [`claimPending`][claim] torna isso
seguro sem locking novo: o que protege contra duas réplicas protege igual contra duas threads.

**4. Cap de concorrência por bureau** (semáforo no provider) e tratamento de `429`, antes de
subir os workers. Sem isso, o item 3 é uma arma apontada para um fornecedor externo.

**5. Métrica de idade da fila** — idade do item mais antigo em `EM_ANALISE`, por faixa. É o SLI
que distingue pico de afogamento; contagem sozinha não distingue.

Fica **explicitamente fora**: autoscaling do número de workers pela profundidade da fila.

## Alternativas consideradas

- **Autoscaling de threads pela profundidade da fila** — rejeitado. No único cenário que
  importa (backfill de 40h), a fila fica grande o tempo todo, então o controlador satura no
  máximo no primeiro minuto e fica lá: entrega exatamente o mesmo resultado de um número fixo,
  em troca de histerese, oscilação e um modo de falha novo. Além disso, o número certo de
  workers não vem da fila — vem de quanta concorrência a BigBoost tolera e de quantas conexões
  existem no pool. Ambos são estáveis e conhecidos: é configuração, não controle de laço.
- **Só escalar réplicas** — o lease já suporta, mas cada réplica contribui com uma única thread
  de processamento; sem o item 3 é a forma mais cara possível de comprar vazão. Continua sendo
  o caminho certo **depois** do paralelismo intra-processo, aí sim como unidade de escala.
- **Rate limit puro no `POST`** — protegeria o pipeline e o custo, mas ao preço de recusar
  tráfego legítimo de tempo real do mesmo cliente. Não separa os dois regimes; só os limita
  juntos.
- **Não fazer nada até haver meta de volumetria** — o plano registra "Volumetria/SLA alvo" como
  bloqueado por decisão de produto. Mas os itens 1 e 2 não dependem da meta: valem para
  qualquer número, porque tratam de isolamento e de autorização de custo, não de capacidade.

## Consequências

- **Positivas:** um cliente deixa de conseguir degradar o SLA de outro; custo de bureau passa a
  ser autorizado antes de incorrido, não conciliado depois; a vazão sobe 4–8x com mudança
  pequena e previsível; o backfill ganha prazo declarável, o que o torna vendável.
- **Negativas / custos:** migration com coluna nova e mudança no claim (caminho quente,
  precisa de teste de concorrência); mais um endpoint público a versionar e documentar; cota
  por tenant é configuração operacional nova, que alguém precisa administrar.
- **Riscos e mitigações:**
  - *Paralelismo sem cap derrubar o bureau* → item 4 antes do 3; `BureauUnavailableException`
    em cascata hoje consome as 5 tentativas de `max-attempts` e marca `FALHA_PROCESSAMENTO`,
    ou seja, o lote se autodestrói silenciosamente.
  - *Fila de revisão humana virar o próximo gargalo* → mesmo 5% de EDD numa base de 500 mil são
    25 mil casos para analista. Não se resolve com thread; entra em case management (Onda 3).
  - *Dimensionamento sem os números do fornecedor* → default conservador e configurável por env
    var, mesmo padrão da ADR-0014: quando o contrato fechar, ajusta sem tocar em código.

## Em aberto

Dependem de resposta da BigBoost e mudam o dimensionamento:

- Limite de concorrência / rate limit contratado — é ele que define o número de workers.
- **Se a API aceita lote** (vários documentos por request). Se aceitar, muda a matemática de
  vazão muito mais que qualquer paralelismo, e o item 3 perde prioridade para um provider em
  lote.
- Preço por faixa de volume — muda a economia do backfill inteiro.

[proc]: ../../services/risk-engine/src/main/java/com/barrier/riskengine/assessment/service/AssessmentProcessor.java
[claim]: ../../services/risk-engine/src/main/java/com/barrier/riskengine/assessment/repository/AssessmentRepositoryImpl.java
