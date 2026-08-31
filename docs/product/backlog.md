# Backlog de produto — Barrier

**Este é o único backlog vivo do projeto.** Antes dele havia quatro planos sobrepostos, e o custo
disso foi medido: quatro itens ficaram marcados como abertos meses depois de resolvidos, e um item
foi executado fora da ordem que o próprio plano exigia (paralelização antes da cota — acelerou a
fatura de bureau em vez de contê-la).

Os planos anteriores estão em [docs/implementation/archive/](../implementation/archive/README.md).
Eles guardam o **racional das recusas** e as hipóteses reprovadas por medição, e continuam valendo
como leitura — mas **nenhum deles é fonte de verdade sobre o que falta**. Só este arquivo é.

Posicionamento vigente: [ADR-0020](../adr/0020-posicionamento-motor-de-decisao-api-first.md) —
**motor de decisão API-first**. O parceiro tem a jornada dele e compra decisão explicável e trilha
auditável. Jornada embarcada (hosted page, SDK, console) é posicionamento B, depois.

---

## Régua: o que conta como pronto

Herdada dos planos anteriores e mantida porque funcionou:

1. **Critério verificável, não intenção.** Não *"implementar X"*, e sim *"como sabemos que X
   funciona"*. Marque `[x]` só com teste cobrindo, e registre o commit ao lado.
2. **Teste que falha do lado seguro.** O padrão que mais pegou defeito neste repositório é o teste
   reflexivo que enumera o código e quebra o build quando algo novo nasce sem cobertura
   (`ApiRouteCoverageTest`, `OpenApiCoverageIntegrationTest`, `ProblemExceptionHandlerTest`). Item
   novo que possa ser esquecido deveria ganhar um desses, não um caso de teste a mais.
3. **A pergunta que classifica qualquer item novo:** *o controle roda, ou só parece que roda?* É o
   modo de falha recorrente aqui — `documentFaceReference` com `@NotBlank` que aceitava `"x"`,
   `KafkaTopicsConfig` expondo um tipo que o `KafkaAdmin` ignora em silêncio, `alerts.yml` escrito
   sobre métricas inertes, invariante de pool escrita em comentário e verificada em lugar nenhum.
4. **Integração não verificada ao vivo não conta como pronta.** Documentação mente; contrato
   mapeado *verbatim* da doc oficial é hipótese, não entrega.

---

## Onde estamos

| Dimensão | Estado |
|---|---|
| Motor de risco e trilha | **Forte.** 16 regras (Strategy), registry com vigência, `evaluated_json` com regras suprimidas e parâmetro efetivo, `config_history`. A auditoria externa classificou a trilha como melhor que a de fornecedores estabelecidos |
| Pipeline e escala | **Provado em parte.** CI, Dockerfile, 5 réplicas em `kind`, `SingletonJobLock`, 3 tópicos × 6 partições, processamento e entrega paralelos com teto. Faltam as verificações de disjunção sob carga e a remedição com bureau real |
| Contrato público | **Recém-aberto.** OpenAPI nos dois serviços com grupo administrativo fora, assinatura de webhook carimbada no tempo. Falta tudo o que vem depois do contrato: guia, sandbox exposto, paginação, histórico de entrega |
| Auditabilidade como produto | **Replay entregue** (`POST /v1/assessments/{id}/replay`, dois modos, sem migration). Falta a **autoria**: `config_history` (V033) continua sem nenhuma leitura as-of |
| KYC de PF | **Bloqueado por fornecedor.** Bureau real implementado e desligado; documentoscopia e biometria devolvem `UNAVAILABLE` em `prod` |
| KYB de PJ | **Não automatiza.** `basic_data` da BigBoost não traz QSA, e `CorporateStructureCoverageRiskRule` é fail-closed de propósito — toda PJ atendida pelo bureau real vai a revisão manual |
| Antifraude | **Inexistente.** `behavior_events` é tabela: zero regras leem o acervo |
| Confiança para assinar contrato | **Aberta.** Sem cota, sem criptografia em repouso, sem retenção. Um dump de backup entrega PII de todos os tenants **e o segredo HMAC**, que permite forjar o callback de KYC de qualquer parceiro |

---

## Sequência recomendada

Ordenada por *"uma instituição regulada assinaria isto?"*, não por esforço. As dependências reais
estão marcadas.

| # | Item | Grupo | Por que aqui |
|---|---|---|---|
| ~~1~~ | ~~**Replay de decisão**~~ ✅ **fechado 2026-08-31** | [2](#2--decisão-auditável--o-diferencial) | Os dados já estavam gravados e ninguém os lia. Fechado sem migration |
| **1** | **Política versionada com vigência e autoria** | [2](#2--decisão-auditável--o-diferencial) | **Agora é o próximo.** O replay já entrega o parâmetro efetivo de cada regra; falta a autoria. `config_history` (V033) segue sem nenhuma leitura as-of |
| 2 | **Cota e rate limit por tenant** | [3](#3--confiança--o-comprador-consegue-assinar) | Vencido: a paralelização foi feita antes dele. Fecha DoS, noisy neighbor e fatura de bureau de uma vez. **Bloqueia** o re-KYC periódico e a ingestão em massa |
| 3 | **Listagem paginada com cursor** | [1](#1--integração--um-dev-externo-integra-sozinho) | Barato, e sem isso o parceiro que perdeu um webhook não tem como reconciliar |
| 4 | **Guia público + sandbox exposto** | [1](#1--integração--um-dev-externo-integra-sozinho) | Em posicionamento A, a integração **é** o produto |
| 5 | **Criptografia em repouso + retenção** | [3](#3--confiança--o-comprador-consegue-assinar) | Bloqueia o questionário de segurança de qualquer comprador sério — antes de bloquear qualquer norma |
| 6 | **Identidade de operador + 4-eyes** | [2](#2--decisão-auditável--o-diferencial) | Depende de 2 |
| 7 | **Fonte de QSA contratada** | [4](#4--cobertura-de-kyckyb) | É o teto do KYB. Comercial, não técnico |

---

## 0 — Destravar

Nada aqui é engenharia, e tudo aqui bloqueia alguma coisa abaixo.

- [ ] **Empresa aberta**
  Ser CLT em geral não impede, mas **leia a cláusula de exclusividade/não-concorrência do seu
  contrato** antes — o produto é do mesmo setor do empregador.
  *Pronto quando:* CNPJ emitido, conta PJ aberta, contador contratado.

- [ ] **Resolver a contradição sobre o bureau de CPF** ⚠️
  [ADR-0014](../adr/0014-bureau-cpf-bigboost.md) escolheu a BigBoost porque seria self-service sem
  CNPJ, e fecha com *"a confirmar com o time comercial deles"* — nunca confirmado.
  [bureau-simulado.md](../implementation/bureau-simulado.md) afirma o oposto: *"não existe bureau
  real de CPF contratável sem CNPJ"*. **Uma decisão de arquitetura está apoiada num "a confirmar",
  e outro documento do mesmo repositório já supõe o contrário.**
  *Pronto quando:* resposta por escrito arquivada em `docs/`, e **um dos dois documentos
  corrigido** — a contradição não pode sobreviver a este item.

- [ ] **Verificar ao vivo as três fontes gratuitas de watchlist**
  CGU (CEIS/CNEP/PEP), OFAC (SDN+ALT) e CSNU/ONU são download público, sem cadastro e sem contrato.
  O 403 registrado foi falta de egress no ambiente de dev, **não** barreira comercial. São a
  cobertura inteira de `SANCTION` em produção, e o layout do CSV da CGU nunca foi conferido contra
  o portal real — o `PepWatchlistSource` avisa isso no próprio Javadoc.
  *Pronto quando:* cada fonte baixada e importada num ambiente com egress, contagem de linhas
  registrada, e **um contract test com fixture do arquivo real** de cada uma, para que mudança de
  layout quebre o build e não a cobertura em produção.

- [ ] **Volumetria e SLA alvo definidos**
  *"Escalável"* não é afirmação verificável sem meta. A capacidade atual **já está medida**
  (ingestão 292 req/s · processamento ~12,5/s com bureau simulado, ~3/s com bureau real): falta o
  alvo, não o número.
  *Pronto quando:* SLI/SLO escritos (latência de decisão, profundidade e idade de fila,
  disponibilidade), e o teste de carga do CI falha contra eles.

---

## 1 — Integração — um dev externo integra sozinho

Em posicionamento A, **esta seção é o produto**, não a documentação dele.

- [x] **OpenAPI gerado, versionado e publicado** — fechado 2026-08-31 (`d36a310`, `c655775`, `77b3707`)
  springdoc 3.0.0 nos dois serviços; grupos `parceiro` (18 rotas) e `admin`, que **nunca** é
  publicado. `OpenApiCoverageIntegrationTest` enumera os controllers pelo bytecode e quebra o build
  quando rota de negócio nasce sem contrato, provado por mutação. Dois defeitos achados lendo o spec
  gerado: `AuthenticatedTenant` publicado como query parameter obrigatório, e nenhum esquema de
  autenticação declarado.

- [x] **Assinatura de webhook carimbada no tempo** — fechado 2026-08-31 (`90a7119`)
  `t=<epoch>,v1=<hex>` sobre `<t>.<corpo cru>`. Feito antes de haver parceiro integrado, porque
  depois seria quebra de contrato.

- [ ] **Listagem paginada com cursor** 🟠
  Não há listagem de avaliações: o parceiro que perdeu um webhook não tem como reconciliar, e hoje
  a resposta é SQL — suporte nível 3 virando produto. A fila da mesa usa `limit` cru sem cursor;
  `GET /v1/risk-rules` devolve tudo.
  *Pronto quando:* cursor estável em **toda** coleção, com teste de página sob inserção concorrente
  (offset com inserção concorrente pula e repete registro — é o defeito que o teste tem que pegar).

- [ ] **Guia de integração público** 🟠
  Quickstart de intake → webhook; **como verificar o HMAC** (o `t=` é assinado, e sem tolerância de
  janela o carimbo é enfeite) e o que fazer com `X-Barrier-Signature-Previous` durante a rotação;
  semântica de `Idempotency-Key`; versão externa do
  [event-catalog.md](../architecture/event-catalog.md).
  *Pronto quando:* um dev externo integra intake + webhook lendo só a doc pública, sem contato — e
  isso foi **observado com uma pessoa real**, não presumido.

- [ ] **Catálogo público de reason codes** 🟠
  Os fatores explicáveis são o produto e não têm dicionário externo. O parceiro recebe
  `IDENTITY_MISMATCH` e `SCREENING_COVERAGE` sem nenhum lugar onde procurar o significado, a
  severidade e a ação esperada.
  *Pronto quando:* cada `RiskRule.code()` e cada `ruleCode` granular tem verbete público, e um teste
  reflexivo falha quando um código novo nasce sem verbete.

- [ ] **Sandbox exposto como produto** 🟡
  O `FakeCpfBureauProvider` já **é** um sandbox completo: atende qualquer CPF válido e usa o prefixo
  `999` + dígito seletor para escolher o cenário (falecido, suspensa, nula, indisponível), com
  tabela em [bureau-simulado.md](../implementation/bureau-simulado.md). Falta expor: ambiente
  público, credencial de teste self-service, tabela de cenários na doc externa.
  *Pronto quando:* um terceiro obtém credencial de sandbox sem falar com ninguém e reproduz os seis
  desfechos de identidade.

- [ ] **API de histórico e reenvio de entrega de webhook** 🟠
  Quando um parceiro disser *"não recebi"*, a resposta hoje é SQL. `deliveries` tem tudo o que a
  resposta precisa e não há endpoint.
  *Pronto quando:* listagem de entregas por avaliação e por período, com o desfecho de cada
  tentativa, e reenvio manual idempotente.

- [ ] **Política de versionamento e changelog de API** 🟢
  `/v1/` existe desde o início, o que está certo, mas não há regra escrita sobre o que é mudança
  compatível. A adição de `identityReused` ao payload foi tratada como retrocompatível por
  raciocínio correto — que não está escrito em lugar nenhum.
  *Pronto quando:* regra publicada, e o diff do spec entre versões gerado no CI.

---

## 2 — Decisão auditável — o diferencial

O ativo mais forte do projeto está **gravado e ilegível**. Esta seção transforma trilha em produto.

- [x] **Replay de decisão** 🔴 — **fechado 2026-08-31**
  `POST /v1/assessments/{id}/replay`, módulo `replay`, **sem migration nenhuma**: o item inteiro
  existia como dado (`evaluated_json`, `identity_check_id`/`screening_result_id` da V028,
  `sources_json`, `ENGINE_VERSION`) e faltava como capacidade.

  ⚠️ **O critério original prometia "bit a bit" e foi corrigido**, porque não é alcançável: regra é
  código, não dado, e o `RiskContext` não é totalmente reconstruível. Afirmar o contrário seria o
  modo de falha que este backlog usa como régua — controle que parece existir e não verifica. As
  duas afirmações verdadeiras entregues no lugar:
  - **`AS_DECIDED`** — dossiê do gravado + **reconferência da aritmética** contra `risk_scores`.
    `TRAIL_INCONSISTENT` precede qualquer outro veredito: é o único que não depende de reconstruir
    insumo nenhum.
  - **`CURRENT_ENGINE`** — regras de hoje sobre a evidência gravada, diff regra a regra, **sem
    nenhuma consulta paga** (garantido por ArchUnit: o módulo não depende de nenhum pacote `client`).

  A lacuna é **apurada, não presumida** — `subject_profiles` não tem histórico mas tem `updated_at`,
  então cadastro intocado desde a decisão não é lacuna, e `SAME_DECISION` continua alcançável.
  `RiskRule.requires()` (sem default, o compilador obriga) faz regra sem insumo virar
  `NOT_REPLAYABLE` em vez de "passou"; `RiskRuleContextDeclarationTest` compara declaração e uso
  real por bytecode, **provado por mutação**.

  *Verificado:* 26 testes novos — reconferência acusa score/recomendação adulterados, banda que não
  reprova sozinha continua valendo na reconferência, regra desligada no registry vira
  `OUTCOME_CHANGED`, insumo ausente não publica resultado, replay não cria linha em `risk_scores`,
  404 para tenant alheio, 409 sem decisão, 401 sem credencial.

- [ ] **Política versionada com vigência e autoria** 🔴
  A plataforma não responde *"qual política estava vigente quando este cliente foi aprovado, e quem
  aprovou essa política"*. `config_history` (V033) é escrita e **nada a lê**: dois `INSERT` no código
  de produção e um `SELECT` num teste. É a metade que falta do replay — ele responde *o quê*, esta
  responde *quem*.
  *Pronto quando:* consulta as-of por `(tenant, rule_code, instante)` devolve a configuração vigente
  com autoria, e uma mudança posterior à decisão **não** aparece no dossiê daquela decisão.

- [ ] **Identidade do operador humano + 4-eyes + fim da admin key global** 🟠
  `reviewed_by` é texto livre; `reviewed_by_key` identifica o **sistema**, não a pessoa. E a chave de
  admin é **estática, única e global**: ela liga e desliga regra regulatória e emite credencial de
  qualquer tenant, sem rotação e sem autoria. Comprometimento = controle total da plataforma sem
  trilha.
  *Pronto quando:* decisão de EDD carrega identidade de pessoa; PEP e mídia negativa exigem dois
  revisores distintos; a admin key global deixa de existir.

- [ ] **Snapshot do contexto da decisão** 🟡 — *derivado do replay*
  `CURRENT_ENGINE` fica permanentemente degradado para PJ (o `CompanyProfile` com QSA/CNAE é
  transiente) e para cadastro alterado depois da decisão. Fechar isso é gravar o contexto no momento
  da decisão.
  ⚠️ **Deliberadamente não feito junto com o replay:** versionar cadastro multiplica dado pessoal sob
  retenção de 10 anos e criptografia em repouso, e a decisão já registrada no projeto é resolver isso
  **junto** com aquele item, não antes. Por isso este item vem **depois** de "Criptografia em repouso"
  e "Retenção", não antes.
  *Pronto quando:* replay de PJ reporta zero lacunas, e o snapshot está sob a mesma política de
  retenção e cifragem do resto da PII.

- [ ] **Shadow mode / simulação de regra contra histórico** 🟠
  Hoje **toda mudança de regra é aposta**: não há como rodar uma regra nova sobre o histórico e ver
  o impacto antes de ligar. Com o replay pronto (fechado), é o passo seguinte natural — e é a resposta ao
  no-code rule builder da Alloy **sem** sacrificar o `ENGINE_VERSION`.
  *Pronto quando:* uma versão candidata do motor roda sobre uma janela do histórico e produz o diff
  de desfechos por regra, sem tocar em `risk_scores`.

- [ ] **Tracing distribuído por etapa** 🟡
  `correlationId` persistido e restaurado através de thread, scheduler e broker resolve o problema
  difícil, e é excelente. Falta o fácil: **sem OpenTelemetry**, *"quanto tempo cada etapa demorou"*
  não tem resposta — só há timer agregado do processamento.

---

## 3 — Confiança — o comprador consegue assinar

O portão real não é o BACEN, é a due diligence de fornecedor da compradora
([ADR-0020](../adr/0020-posicionamento-motor-de-decisao-api-first.md)).

- [ ] **Cota e rate limit por tenant** 🔴
  Fecha três coisas de uma vez: DoS trivial (290 req/s medidos de ingestão contra nenhuma barreira),
  noisy neighbor (um tenant em bulk **para o onboarding de todos**) e fatura de bureau ilimitada.
  ⚠️ **Vencido.** O plano anterior dizia explicitamente que paralelizar vem *depois* da cota; foi
  feito antes. Hoje o único limite de consultas pagas é o semáforo, que é **por pod** — com 5
  réplicas são 20 simultâneas, sem isolamento por parceiro. Paralelizar sem cota acelerou a fatura.
  *Pronto quando:* cota por tenant no intake **e** no lote de processamento; teste provando que
  tenant em bulk não atrasa o p99 do vizinho. Fecha junto a limitação já documentada do re-KYC
  periódico (*"o teto é global e a ordem por antiguidade não isola tenants"*) e o
  [ADR-0015](../adr/0015-ingestao-em-massa-faixa-separada.md).

- [ ] **Criptografia em repouso** 🔴
  Grep por `encrypt|Cipher` no projeto: **zero**. Em claro hoje: CPF/CNPJ, nome, nascimento,
  endereço, `raw_response` do bureau, e **o segredo HMAC na coluna**. Este último não é só exposição
  de PII: um dump de backup permite **forjar o callback de KYC de qualquer tenant** — exatamente o
  ataque que o segredo por tenant existe para impedir.
  *Pronto quando:* campos de PII e o segredo HMAC cifrados, com rotação de chave testada.

- [ ] **Retenção com expiração e legal hold** 🔴
  Sem política, sem job, sem coluna de prazo. O `raw_response` guarda dado pessoal indefinidamente,
  e o item aberto do histórico de `subject_profiles` foi explicitamente adiado para decidir **junto**
  com este.
  *Pronto quando:* prazo por categoria de dado, job de expiração com teste, e legal hold que suspende
  a expiração de um caso sob investigação.

- [ ] **SSRF residual na URL de webhook** 🟡
  A URL é validada só por esquema e host local. `https://169.254.169.254/…` e `https://10.0.0.5/`
  passam — POST autenticado para metadata de cloud ou rede interna. Estar atrás de admin reduz para
  médio, não elimina.
  *Pronto quando:* faixas privadas e link-local negadas **após resolução DNS**, e re-resolução no
  envio (rebinding).

- [ ] **Vínculo de tenant nascendo de um `POST`** 🟡
  O escopo por vínculo está certo (404 sem vínculo), mas **o vínculo nasce de um `POST`**: um tenant
  que suspeite de um CPF cria uma avaliação para obter vínculo e passa a poder consultá-lo. Mitigado
  no cadastro (V024), não no fluxo. Sem cota, sondar é só questão de custo.
  *Pronto quando:* cota (acima) **e** decisão revista sobre `POST` criar vínculo sem intenção
  declarada.

- [ ] **SSO/OIDC + RBAC** 🔵
  Enterprise. Chega com o comprador que exige, não antes — mas o item de identidade de operador
  (grupo 2) é pré-requisito dele e vem muito antes.

- [ ] **Runbook, DR com RPO/RTO, scan de dependência no gate** 🟠
  O scan de CVE já roda no CI; falta ele **reprovar** o build e falta o resto. Stack de vanguarda
  (Java 25 + Boot 4.0) tem superfície de CVE pouco trilhada.

---

## 4 — Cobertura de KYC/KYB

O que separa *"temos a arquitetura"* de *"temos a capacidade"*. Quase tudo aqui é comercial.

- [ ] **Fonte de QSA contratada (UBO ≥25%)** 🔴 — **o teto real do KYB hoje**
  **KYB sem UBO não é KYB.** `basic_data` da BigBoost não traz quadro societário, e
  `CorporateStructureCoverageRiskRule` é fail-closed de propósito: quando o bureau real atende,
  **toda PJ vai para revisão manual**. Vender assim é vender fila.
  ⚠️ E `BrasilApiBureauProvider` é hoje a **única fonte de QSA do repositório** — trocá-la sem
  dataset substituto levaria o KYB automatizado a zero. O item não é "trocar de provider", é
  **adquirir fonte com QSA**. Ver [ADR-0018](../adr/0018-custo-de-navegacao-ubo.md).
  *Pronto quando:* percentual de participação disponível, navegação até ≥25%, e o guard de cobertura
  deixando de disparar em PJ atendida por bureau real.

- [ ] **Provedor de documentoscopia e biometria contratado** 🟠
  O pipeline está pronto e correto (consentimento na assinatura, gate documento→biometria,
  divergência ≠ campo faltando, reavaliação automática); **a capacidade não existe**. Em `prod`,
  `UnavailableDocumentVerificationProvider` e `UnavailableBiometricVerificationProvider` devolvem
  sempre `UNAVAILABLE`. Com o gate obrigatório, a ausência de provedor **trava a frente inteira**,
  não metade.
  *Pronto quando:* provedor contratado e exercitado ao vivo, e o `AssuranceProviderReadinessGuard`
  deixando de avisar em `prod`.

- [ ] **Bureau real de CPF ligado** 🟠
  `BigBoostBureauProvider` está implementado e testado contra o JSON documentado; contratar é ligar
  a flag. Depende do item de contradição do grupo 0.

- [ ] **Contract tests das quatro integrações nunca exercitadas** 🔴
  BigBoost CNPJ (*"o schema ainda não foi verificado contra a API real"*), Datavalid/Serpro (*"sem
  egress de rede neste ambiente"*), CSV da CGU (*"403 do ambiente de dev"*) e PagerDuty (*"nunca
  exercitado"*). Todas mapeadas *verbatim* da documentação — e documentação mente. É o **risco de
  cronograma mais subestimado do projeto** e o único que não se resolve com mais código.
  *Pronto quando:* uma chamada real, com credencial de teste, gravada como fixture de contract test
  para cada uma das quatro.

- [ ] **Verificação de endereço para quem não tem CNH** 🟡
  O Datavalid confere o endereço declarado contra o registrado na **CNH** (SENATRAN). Quem não tem
  CNH, ou tem CNH sem endereço, **continua sem nenhuma verificação de endereço** — o campo segue
  exatamente como antes daquela entrega para esse subconjunto.
  *Pronto quando:* segunda fonte de verificação de endereço, independente da CNH.

- [ ] **Mídia negativa real** 🟡
  `StubNegativeMediaProvider` casa contra uma lista vazia por padrão. `NegativeMediaProvider` já tem
  `authoritative()`, e `ScreeningCoverageRiskRule` só exige cobertura de `ADVERSE_MEDIA` quando
  existe provider autoritativo — então contratar um é ligar, sem mudança de motor.

- [ ] **Caminho síncrono de decisão** 🟠
  O pipeline é **100% assíncrono via poller**; não existe API que devolva score em <300ms. Para
  antifraude transacional — que o nome do produto promete — isso é bloqueador **arquitetural**, não
  detalhe de latência.

---

## 5 — Operação e prova de escala

- [ ] **Verificações de escala que ficaram escritas e não executadas** 🟠
  `deploy/verify-disjuncao.sh` (disjunção entre processos sob carga) foi escrito e **nunca rodado
  ponta a ponta**; matar um pod no meio de um lote nunca foi testado; o KEDA nunca foi instalado.
  Herdado do plano de escala horizontal, e é exatamente a classe de item que aquele plano existia
  para eliminar.

- [ ] **Remedir a vazão com bureau real** 🟠
  A vazão do pipeline paralelo foi medida só com **bureau simulado**. Com bureau real o gargalo é o
  pool de conexões, não as threads. Sem isso, o [ADR-0015](../adr/0015-ingestao-em-massa-faixa-separada.md)
  não pode ser considerado fechado.

- [ ] **Head-of-line blocking na entrega de webhook** 🟠
  O `allOf().join()` faz o ciclo durar o elemento mais lento: 3 destinos pendurados param 97 entregas
  prontas. E não há circuit breaker por endpoint — cada retry de um tenant morto paga o timeout
  inteiro e ocupa um slot do lote global.
  *Pronto quando:* 1.000 endpoints com 10% lentos e o tenant saudável **sem atraso mensurável**.

- [ ] **Teto de bureau é por pod** 🟠
  O semáforo de workers limita consultas pagas simultâneas **por instância**; no cluster o teto real
  é 5×. O Javadoc afirma um teto que não é o teto. Some com o item de cota (grupo 3).

- [ ] **Scheduler dedicado por job** 🟡
  8+ jobs em 4 threads (`AssessmentProcessor`, `OutboxRelay`, `WatchlistImporter`, `AlertEvaluator`,
  `AssuranceResultPoller`, `PeriodicReassessmentJob`, purga de idempotência,
  `DeliveryReconciliationJob`). A importação das 03:00 e o re-KYC das 03:30 competem com o relay. O
  dimensionamento foi escolhido quando eram três jobs.

- [ ] **Métricas de outbox, cobertura de watchlist e entrega de webhook** 🟡
  As regras correspondentes no `alerts.yml` estão **escritas e inertes** — o que é pior que ausência,
  porque dá cobertura falsa.

- [ ] **Chaos e carga no CI** 🟠
  Nenhum teste de banco fora, Kafka fora, provider lento ou retornando lixo. O arnês de carga (k6)
  existe e roda à mão. Depende dos SLI/SLO do grupo 0.

- [ ] **Cache de registry e config por tenant** 🟢
  ~10 queries extras por avaliação. Não é o gargalo; é gordura no caminho quente.

- [ ] **Redesenhar `arquitetura-atual.svg`** 🟢
  Desenha 4 módulos de 16. ⚠️ O aviso atual no [diagrams/README.md](../diagrams/README.md) diz que
  ele cita *"módulos que nunca existiram"* — **isso está errado**: `geoip`, `device`, `credit` e
  `history` existem, nas três branches órfãs abaixo. O diagrama foi desenhado a partir de trabalho
  que nunca entrou.

---

## 6 — Antifraude e monitoramento transacional

Nesta ordem de custo/benefício. Hoje o projeto tem **1,0/10** aqui, e a nota está correta.

- [ ] **Regras que leiam `behavior_events`** 🟠
  `behavior_events` é **tabela, não antifraude**: zero regras leem o acervo. É o consumidor que
  justifica o F8 retroativamente, e é onde a política de disparo (quando um fato comportamental
  gera reavaliação) precisa ser decidida — deliberadamente adiada na entrega da ingestão.

- [ ] **Device fingerprint → velocity → grafo de entidades** 🟠
  Pessoa × empresa × endereço × device × conta. Parte já existe nas branches órfãs (abaixo).

- [ ] **Monitoramento transacional (Circular 3.978)** 🔵
  Consumindo `behavior_events`. Depende do caminho síncrono de decisão (grupo 4).

- [ ] **COAF/SISCOAF e workflow de atividade suspeita** 🔵
  Obrigação da instituição contratante, não do fornecedor, nesta etapa — mas vira exigência de
  contrato assim que o comprador for uma instituição financeira de porte.

---

## Trabalho órfão — três branches nunca integradas

Descobertas ao reconciliar o backlog: existe trabalho real fora de `main`, e a documentação o
descrevia como inexistente.

| Branch | O que traz | Custo para entrar |
|---|---|---|
| `feat/network-signals` | `GeoMismatchRiskRule` (UF do IP × endereço cadastral) + `DeviceReuseRiskRule` + módulo `device` (`device_seen`). Intake ganha `ip`/`deviceId` opcionais | 36 arquivos, +699 linhas. **Migrations V019/V020 colidem** com as atuais (`watchlist_document_partial`, `risk_rule_registry_screening_coverage`) — renumerar |
| `feat/phone-email-signals` | `PhoneVoipRiskRule`, `EmailDisposableRiskRule`, `EmailReuseRiskRule` | 55 arquivos, +1253 (inclui a anterior) |
| `feat/history-credit-signals` | `subject_history` + `HistoryRiskRule` (chargeback, PIX devolvido, denúncia, conta encerrada por fraude) + `CreditScoreProvider` (stub) | 75 arquivos, +1960 (inclui as duas anteriores). **V023 colide** |

**Avaliação:** são o item *device fingerprint → velocity* do grupo 6, já escritos, seguindo o padrão
correto (Strategy puro, sinais calculados no `AssessmentProcessor` e injetados no `RiskContext`, sem
regra acessando repositório). O que falta para qualquer uma entrar: **renumerar as migrations**,
subir o `ENGINE_VERSION` (regra nova que pontua muda decisão), revalidar contra o ArchUnit e contra
o `OpenApiCoverageIntegrationTest` (a de histórico adiciona
`POST /v1/subjects/{document}/history`), e decidir se `CreditScoreRiskRule` deve existir inerte —
uma regra que nunca dispara aparece como `NOT_TRIGGERED` na trilha de toda avaliação e sugere um
controle que na prática não existe.

**Decisão:** não integrar agora — escopo novo colide com a sequência acima. Registrado aqui para
parar de ser invisível, e o aviso errado no `diagrams/README.md` foi corrigido.

---

## Fora de escopo, com motivo

Recusa com critério, no padrão do schema registry (F9): para a pergunta não voltar em três meses sem
o racional junto.

| Item | Por que fica de fora |
|---|---|
| Hosted page / SDK de captura | Posicionamento B. Muda a exposição LGPD — hoje o sistema nunca toca em dado biométrico ([ADR-0020](../adr/0020-posicionamento-motor-de-decisao-api-first.md)) |
| **UI** da mesa de análise | B. Em A o analista é do parceiro, na ferramenta do parceiro. ⚠️ O módulo `mesa` **continua valendo**: fila, SLA pausável e timeline são entregues **como API**, no grupo `parceiro` do contrato — é a UI que é B, não a capacidade |
| Regras editáveis pelo parceiro (self-service) | Dá ao regulado o botão de afrouxar o próprio controle de PLD-FT, e o compliance officer do comprador **não quer** que a área de negócio dele tenha esse botão. A forma regulatória disso é política versionada com vigência, autoria e replay (grupo 2) |
| Regra-como-dado editável em runtime | Sacrificaria `ENGINE_VERSION` e a trilha reproduzível |
| Schema registry de eventos | Entra quando o produtor deixar de ser único, ou na primeira quebra real a coordenar entre times que não compilam juntos. Hoje o `commons` dá compatibilidade em tempo de compilação. O [event-catalog.md](../architecture/event-catalog.md) é a mitigação, e só funciona se for atualizado no mesmo PR que muda o evento |
| Reuso de verificação de identidade entre tenants | Repetiria o erro que o ADR-0012 corrigiu no cadastro. Opt-in futuro, com ADR próprio |

⚠️ **`SerproBureauProvider` é esqueleto morto** — sem `@Component`, `check()` só lança
`BureauUnavailableException`. Não conte com ele em estimativa nenhuma.

---

## Bloqueado por fornecedor ou decisão

| Item | Bloqueio | Efeito hoje |
|---|---|---|
| Bureau real de CPF | Credencial (BigBoost/Serpro). **Não existe API gratuita legítima** — o que se anuncia como tal é scraping com bypass de captcha ou base vazada | `CpfBureauReadinessGuard` impede `prod`; PF inviável. Dev usa o bureau simulado |
| Provedor KYB (UBO) | Contrato | KYB só de 1º grau; toda PJ do bureau real vai a revisão manual |
| Documentoscopia e biometria | Provedor é B2B com contrato | Providers de `prod` devolvem sempre `UNAVAILABLE`; o gate trava a frente inteira |
| Mídia negativa real | Contrato | Stub com lista vazia |
| Validação do CSV de PEP | Egress do ambiente | Fonte escrita sem verificação |
| Dimensionamento de workers e cap de bureau | Contrato BigBoost | Limite de concorrência, suporte a consulta **em lote** e preço por faixa são desconhecidos. Se a API aceitar lote, a vazão muda mais que qualquer paralelismo |

**Caminho alternativo para CPF:** gov.br Login Único (OIDC) devolve CPF e nome já verificados (selo
prata/ouro implica validação biométrica com o TSE) a custo baixo — exige credenciamento, cujas
condições atuais para empresa privada precisam ser confirmadas.

---

## O limite que nenhum item acima resolve

Registrado porque metade do valor de um backlog é o que ele admite.

Uma pessoa não vende KYC para instituição regulada. Não por causa do código — por causa de plantão
24/7 (pipeline parado às 3h é receita parada do cliente), *bus factor* (está em todo questionário de
fornecedor, e "sou eu" reprova), custo de pentest e certificação, e ciclo de venda de 6 a 18 meses.

A auditoria escreveu a mesma frase: *"a distância entre production-ready e enterprise-ready não é
técnica, é organizacional — e é onde o projeto de uma pessoa encontra o limite real."*

**Consequência prática para o sequenciamento:** o primeiro parceiro não deve ser banco. Deve ser
quem tem obrigação real de KYC e due diligence de fornecedor leve — operadores de aposta (Lei
14.790/2023) e VASPs (Lei 14.478/2022) são as duas janelas abertas hoje. E procurar um sócio
comercial ou de compliance destrava mais itens desta lista do que qualquer coisa que se possa
programar.
