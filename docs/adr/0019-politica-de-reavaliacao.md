# ADR-0019: Política de reavaliação — gatilho, materialidade e intervalo mínimo

- **Status:** Aceito
- **Data:** 2026-08-15

## Contexto

O `RescreeningService` tem três travas, e todas as três são contra **avalanche de importação**:
importação sobre base vazia é linha de base e não dispara; teto de subjects por importação aborta
e grita; uma avaliação por `(subject, tenant)` por importação. O `AssuranceReassessmentTrigger`
tem uma quarta, a janela de dedup de 5 minutos.

Nenhuma delas responde a pergunta diferente: **quando reavaliar um cliente é legítimo?** Um PATCH
de cadastro, um reintake, um cliente tocado por duas fontes na mesma semana — hoje tudo dispara ou
nada dispara, sem critério declarado. E há um buraco de trilha: um rescreening que **não** gerou
avaliação é hoje indistinguível de um que nunca rodou. O auditor não consegue separar "o controle
rodou e concluiu que não havia o que reavaliar" de "o controle estava desligado" — que é
exatamente a distinção que o Barrier faz questão de fazer em toda regra de risco.

O ecossistema Origem resolveu isso em produção no [`adr-reanalise-gatilhos.md`][origem], com três
eixos independentes e uma matriz de nove gatilhos. Ver [lições do Origem][licoes], prioridade 4.

## Decisão

Vamos exigir, para reavaliar **sem fato adverso novo**, a conjunção de três condições
independentes:

1. **Gatilho** — algo aconteceu que justifica reolhar o cliente;
2. **Alteração material** — o que aconteceu muda algo que a decisão usa;
3. **Intervalo mínimo** desde a última decisão, por nível de risco corrente.

E vamos registrar **toda** decisão de não reavaliar, com o motivo.

### Intervalo mínimo por nível

A escala do Barrier é invertida em relação à do Origem (aqui **maior = pior**), então as faixas
F1–F5 mapeiam assim:

| Nível corrente (Barrier) | Dias | Leitura |
|---|---|---|
| `LOW` | 1095 | cliente bom se reavalia a cada 3 anos |
| `MEDIUM` | 730 | |
| `HIGH` | 365 | |
| `CRITICAL` | 183 | cliente ruim se reavalia a cada 6 meses |
| sem projeção (desconhecido) | 183 | **fail-safe pelo pior caso** |

O nível corrente vem de `subject_risk_state` (ADR/entrega F3). Cliente sem projeção é tratado como
o pior caso, não como o melhor: desconhecido não é sinônimo de bom, e o erro barato aqui é
reavaliar demais.

### Matriz de gatilhos

| Gatilho | Exige materialidade? | Respeita intervalo? |
|---|---|---|
| `WATCHLIST_DELTA` — lista passou a apontar o cliente | não | **não — bypass** |
| `ASSURANCE` — documentoscopia/biometria registrada | não | não |
| `MANUAL` — pedido explícito de operador | não | não |
| `PROFILE_PATCH` — cadastro alterado | **sim** | **não — bypass** |
| `REINTAKE` — parceiro submeteu o mesmo cliente de novo | **sim** | sim |
| `PERIODIC` — reavaliação de rotina | não | sim |

### Patch cadastral reavalia — decisão de produto, 2026-08-15

`PROFILE_PATCH` exige alteração material **e fura o intervalo mínimo**. A primeira versão desta
política fazia o contrário (exigia materialidade *e* respeitava o intervalo), e o efeito prático
seria nenhum: um cliente `LOW` só voltaria a ser avaliado depois de 1095 dias, então quase todo
patch morreria no intervalo e a checagem de materialidade seria decorativa — o comportamento
observável continuaria o de antes da política, que é não reavaliar por cadastro.

A consequência é que **o freio deste gatilho passa a ser inteiramente a materialidade**, e por isso
ela é apurada campo a campo (`MaterialProfileChange`), não por "houve um PUT":

- material é o campo que alguma regra lê ou que o `RegistrationCompleteness` exige — nascimento,
  nacionalidade, endereço, telefone, ocupação, renda, CNAE, abertura, capital, representante legal
  e QSA. `email` e a descrição do CNAE ficam de fora: ninguém os lê;
- valor igual não conta, inclusive com diferença só de caixa, espaço ou escala decimal. O `PUT` é
  progressivo e mesclado: reenviar o mesmo endereço é chamada legítima e frequente, e sem essa
  comparação o parceiro que sincroniza cadastro em lote pagaria uma consulta de bureau por cliente
  para não ter mudado nada;
- lista de sócios vazia é "não informado", não "zerei o quadro societário".

**O laço que precisou ser fechado junto:** o `AssessmentProcessor` grava no mesmo cadastro os dados
objetivos que o bureau devolve, no meio da avaliação. Se esse caminho notificasse alteração
material, toda avaliação geraria outra avaliação, cada uma com sua consulta paga, indefinidamente.
Os dois caminhos foram separados no tipo — `SubjectProfileService.update` (parceiro, notifica) e
`enrichFromBureau` (bureau, não notifica) —, e não por um parâmetro booleano, porque a diferença é
grande demais para se errar por omissão. Vale a regra: **o parceiro declara, o bureau confirma; só
a declaração é fato novo.**

Dedup por janela curta (`barrier.subject.profile.reassessment-window`, `PT5M`) evita que um
formulário salvo campo a campo vire uma avaliação por tecla.

### ⚠️ O erro que esta política existe para não cometer

**Intervalo mínimo não se aplica a rescreening por watchlist.** Entrada nova em lista de sanção é
*fato adverso novo* sobre o cliente; suprimi-la porque "já reavaliei há 30 dias" descumpre a
Circular 3.978. Na matriz do Origem esse caso é o gatilho D, com bypass explícito — e é o item que
mais fácil se erra ao importar a política, porque a leitura apressada é "intervalo mínimo economiza
consulta, logo aplique em tudo".

O intervalo governa reavaliação **sem fato novo** (periódica, cadastral). O custo do rescreening se
controla pelo **reuso de verificação de identidade** (entregue, V040): a lista mudou, o titular não
— reaproveitar a identidade não é ignorar que fatos mudam, é reconhecer que *esse* fato não é o que
mudou. Controlar custo deixando de reavaliar seria trocar dinheiro por descumprimento.

`ASSURANCE` também faz bypass, por razão análoga: uma tentativa de biometria é evento sobre a
identidade daquele cliente, não rotina. O throttle dele continua sendo a janela de 5 minutos, que é
antiavalanche e não política.

### Trilha

Toda passagem pela política grava `reassessment_decisions`: gatilho, se reavaliou, e o motivo de
não ter reavaliado (`intervalo_minimo`, `sem_alteracao_material`, `ja_reavaliado_nesta_importacao`).
É o `reanalise_decisao` do Origem, e é o que fecha o buraco de "não rodou" versus "rodou e decidiu
que não".

## Alternativas consideradas

- **Só intervalo mínimo, sem matriz de gatilhos.** Metade do custo de implementação e é a versão
  que descumpre a Circular 3.978 — sem a coluna de bypass, sanção nova entra na mesma regra do
  PATCH de telefone.
- **Materialidade para tudo.** Elegante e errado pelo mesmo motivo: exigir que a watchlist prove
  "alteração material no cadastro" para reavaliar inverte o sentido do controle — o fato novo está
  na lista, não no cadastro.
- **Guardar a decisão só em log.** Mais barato que uma tabela. Descartado: log não é trilha de
  auditoria (retenção diferente, sem consulta por subject, sem garantia de escrita), e o que se
  quer provar aqui é justamente que o controle rodou.

## Consequências

- **Positivas:** reavaliação passa a ter critério declarado e auditável; "não reavaliou" deixa de
  ser indistinguível de "não rodou"; a política é um ponto único onde o apetite muda, em vez de
  regra espalhada por cada gatilho.
- **Negativas / custos:** mais uma tabela escrita no caminho de reavaliação, inclusive quando a
  resposta é "não". É deliberado — a linha que diz "não reavaliei porque faltavam 300 dias" é
  exatamente a que o auditor pede.
- **Riscos e mitigações:** o risco real é alguém, no futuro, estender o intervalo ao
  `WATCHLIST_DELTA` para economizar. Mitigação: o bypass é propriedade do enum de gatilho e tem
  teste dedicado que falha se mudar — a política é código, não convenção.

[origem]: ../../../Backend/bmp-origem-back/Docs/Cadastro%20Unico/adr-reanalise-gatilhos.md
[licoes]: ../implementation/licoes-do-origem.md
