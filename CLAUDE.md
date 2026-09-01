# Barrier — contexto do projeto

Plataforma de KYC / PLD-FT para atender às normas do Banco Central. Modelo atual: **motor
de risco** (operador LGPD), evoluindo para plataforma completa. Ver [README](README.md).

## Ao implementar código, siga SEMPRE

- **O QUE VEM AGORA (leia primeiro):** [docs/product/backlog.md](docs/product/backlog.md)
  — **o único backlog vivo.** Antes havia quatro planos sobrepostos, e o custo foi medido: quatro
  itens ficaram marcados como abertos meses depois de resolvidos, e a paralelização foi feita antes
  da cota que o próprio plano exigia primeiro. Consulte a **sequência recomendada** antes de propor
  trabalho novo. Em execução agora: **replay de decisão**.
- **Posicionamento do produto:** [ADR-0020](docs/adr/0020-posicionamento-motor-de-decisao-api-first.md)
  — motor de decisão **API-first**. O parceiro tem a jornada dele e compra decisão explicável e
  trilha auditável; hosted page/SDK/UI da mesa são posicionamento B, depois. **Em A, a integração é
  o produto** — contrato, guia e sandbox não são acessórios.
- **Padrões de código:** [docs/implementation/coding-standards.md](docs/implementation/coding-standards.md)
- **Lições do BMP Origem:** [docs/implementation/licoes-do-origem.md](docs/implementation/licoes-do-origem.md)
  — estudo comparativo com a esteira de KYC que roda em produção na BMP (Origem/Mishmar/
  bureaus-manager/tzofe): o que importar, em que ordem, e **o que não copiar**.
- **Planos encerrados (arquivo):** [docs/implementation/archive/](docs/implementation/archive/README.md)
  — remediação, auditoria externa, escala horizontal, fila-origem (drenada), plano da Risk Engine e
  plano de produto. **Não são backlog e não são fonte de verdade sobre o estado atual** — guardam o
  racional das recusas (por que não regra customizável, por que não schema registry, por que não
  trocar a BrasilAPI) e as hipóteses reprovadas por medição. Item marcado `[ ]` lá pode já estar
  pronto; a tabela de reconciliação no README do arquivo lista os que estavam.
- **Decisões de arquitetura:** [docs/adr/](docs/adr/) (ADR-0009 define o corte atual)

Existe a skill `barrier-implementation` com o checklist operacional — use-a antes de
escrever ou revisar código de implementação.

## Regras que não se negociam

- Camadas: `controller → service → repository`; integração externa só por interface
  (`client`). Validado por ArchUnit.
- Eventos: sempre via **transactional outbox** (nunca publicar direto no Kafka).
- Consumidores idempotentes; Kafka é at-least-once.
- Regras de risco/screening como **Strategy**, com fatores explicáveis.
- Migrations Flyway imutáveis; um schema por serviço.
- Nunca logar CPF/CNPJ sem mascarar; segredos por env.
- Testes: unidade + integração (Testcontainers) + arquitetura (ArchUnit). Bug corrigido
  vem com teste.

## Stack

Java 25 · Spring Boot 4.0 · Maven (monorepo) · PostgreSQL + Flyway · Kafka · MapStruct ·
JUnit 5 / Testcontainers / ArchUnit. Pacote raiz `com.barrier.<contexto>`.

## Estado atual

Subjects (ADR-0011): o cliente final (CPF/CNPJ) é um **subject global** — 1 registro por
documento (`subjects`, UNIQUE). A visibilidade é por associação (`tenant_subjects`): o `POST`
acha-ou-cria o subject e garante o vínculo; `GET /v1/subjects/{documento}` só retorna se o
tenant tem vínculo (senão 404 — não vaza cliente de outra empresa). `assessments.subject_id`
liga a avaliação ao subject. Decisão de aceitar/recusar (EM_REVISAO) é **por tenant no
assessment**, nunca no subject. Cache compartilhado de dados objetivos entre tenants = futuro
opt-in.

Revisão manual (EDD): avaliação em `EM_REVISAO` é decidida por humano via
`POST /v1/assessments/{id}/decision` (`ReviewDecisionRequest`: APPROVE/REJECT + `reviewedBy` +
`reason`), escopado por tenant. `Assessment.decide` só vale a partir de EM_REVISAO (senão 409),
grava a trilha (`reviewed_by`/`review_reason`/`reviewed_at`, migration V013) e reemite
`barrier.assessment.completed` via `AssessmentEventPublisher` (o webhook entrega o desfecho
final). A resposta e o `GET` expõem a trilha de review.

Multi-tenancy: cada avaliação pertence a um **tenant** (cliente da API). Header `X-Client-Id`
→ `TenantService.resolve` (tabela `tenants`, seed `default`); `assessments.tenant_id` +
`deliveries.tenant_id`; o evento carrega `tenantId`; `GET` é escopado por tenant. Pré-auth
confia no header; quando a API key chegar, o tenant será derivado da key (header ignorado).
Termo `tenant` no código evita colisão com os vários "client" (bureau/HTTP).

Bureaus (identity): cadeia com prioridade (`@Order`) + fallback — bureau indisponível cai
para o próximo; resultado definitivo encerra. CNPJ real via BrasilAPI; CPF no stub. O
BrasilAPI agora também extrai um `CompanyProfile` (abertura/CNAE/QSA) — transiente, não
persistido; `IdentityService.verify` devolve `IdentityResult(check, company)` e o perfil trafega
até o motor de risco pelo `RiskContext`. Regras de PJ que consomem isso: `NewCompanyRiskRule`
(empresa recém-aberta), `SensitiveCnaeRiskRule` (CNAE sensível a PLD-FT) e
`CorporateStructureRiskRule` (KYB — sócio estrangeiro/PJ no QSA de 1º grau; árvore até 3º grau
ainda depende de provedor KYB). `ENGINE_VERSION` = `barrier-risk-rules/1.8.0`.

Watchlists (screening): **ingeridas** (ADR-0010) — `WatchlistImporter` (ApplicationRunner +
@Scheduled) carrega `WatchlistSource`s numa tabela `watchlist_entries`; `LocalWatchlistProvider`
casa por documento (exato). Fontes: semente `resources/watchlists/ceis-seed.csv`; CGU real
(`CeisWatchlistSource`/`CnepWatchlistSource` — baixam o ZIP do Portal da Transparência, parseiam
CSV ISO-8859-1 `;`); OFAC (`OfacWatchlistSource` — `sdn.csv` + `alt.csv`, entradas por nome sem
documento, apelidos viram linhas próprias). CSNU/ONU (`UnWatchlistSource` — XML consolidado, INDIVIDUALS+ENTITIES, cada alias vira entrada,
sem documento; obrigação da Lei 13.810/19, **ligada por padrão em prod**). Fontes que baixam são
gated por config (`barrier.watchlist.cgu.enabled` / `.ofac.enabled` / `.un.enabled`, off por padrão
em dev). Cobertura de SANCTION em prod vem de OFAC + CSNU (CEIS/CNEP agora são DEBARMENT).
Match por **nome (fuzzy)**: `FuzzyNameWatchlistProvider` (Jaro-Winkler + `NameNormalizer` sobre
as entradas sem documento; limiar/`min-name-length` configuráveis). Evolução: índice/blocking
para volumes grandes do OFAC.

Fases 1-4 concluídas (build verde). Fase 1: intake + outbox. Fase 2: Identity.
Fase 3: Screening. Fase 4: motor de risco — `RiskRule` (Strategy) devolve `RiskResult`
padronizado (score/severidade/motivo/evidências/recomendação); `RiskScoringService` soma
0–1000 em bandas LOW/MEDIUM/HIGH/CRITICAL + override, grava `risk_scores` com a versão do
motor (`ENGINE_VERSION`). O `AssessmentProcessor` reúne identidade+screening e delega a
decisão ao motor; fatores explicáveis ficam no assessment (coluna `factors`) e voltam no GET.

Convenções novas: domain do módulo `risk` dividido em subpastas (`domain/enums`,
`domain/model`) — os módulos assessment/identity/screening ainda usam domain plano (retrofit
pendente). Regras de risco: adicionar fonte = adicionar uma `RiskRule`, sem tocar no motor.
`ENGINE_VERSION` deve subir a cada mudança de regra/peso (auditoria).

Webhook API (`services/webhook-api`, pacote `com.barrier.webhook`) concluída: consome
`barrier.assessment.completed`, entrega no endpoint do cliente com HMAC (`HmacSigner`),
retry/backoff (`DeliveryRetryScheduler`), idempotência por `eventId` e rastreio em
`deliveries`. Usa schema Postgres próprio `webhook` (Flyway `schemas=webhook`); escaneia só
`com.barrier.webhook` (não puxa os beans de outbox do commons).

Disjuntor por bureau (`com.barrier.riskengine.resilience`): `CircuitBreaker` CLOSED/OPEN/HALF_OPEN
escrito à mão (sem biblioteca de resiliência), um por provider via `CircuitBreakerRegistry`
(`barrier.resilience.failure-threshold`=5, `open-duration`=PT30S, estado por instância).
`IdentityService` chama `allowRequest()` antes de sair para a rede — aberto conta como
indisponibilidade daquele bureau e a cadeia cai para o próximo; sem próximo, `UNAVAILABLE`.
Só `BureauUnavailableException` alimenta o disjuntor (erro de programação não é provider fora do
ar). Meia-abertura libera uma sondagem só. Watchlist e entrega de webhook seguem sem breaker.

Falha no consumo (webhook-api): o listener **não engole mais** exceção. `MalformedEventException`
(JSON/payload ilegível) não é retentada e vai direto para `<tópico>.DLT`; falha transitória sobe e o
`DefaultErrorHandler` (`KafkaErrorHandlingConfig`) retenta com backoff exponencial **sem commitar o
offset**, indo para a DLT só ao esgotar `barrier.webhook.consumer.retry-max-elapsed`.
`DeliveryReconciliationJob` (@Scheduled, janela `PT6H`) relê o tópico com um consumidor avulso
(`assign`, sem commit) e cria entrega para toda decisão sem uma — é o que recupera o que ficou na
DLT ou passou enquanto o consumidor estava fora. Limitado pela retenção do Kafka.
**Quem recupera o quê está fixado no [ADR-0017](docs/adr/0017-ownership-de-recovery.md)** — um
dono por estado de falha, com proibições explícitas (o reconciliador não reprocessa avaliação; o
retry não relê o tópico; `FALHA_PROCESSAMENTO` e `UNAVAILABLE` de bureau não são re-enfileirados
por ninguém). Mecanismo novo de recuperação atualiza aquela tabela.

Endpoint de webhook por tenant: o destino sai de `webhook_endpoints` (V004), resolvido pelo
`tenantId` **do evento** (`WebhookEndpointService.resolveTargetUrl`). Sem registro, não entrega —
e loga; endpoint desativado (`active=false`) também não cai no destino global.
`barrier.webhook.target-url` continua existindo só como fallback de dev: em `prod` a aplicação não
sobe com ele definido (`GlobalTargetUrlReadinessGuard`), porque é um destino único para todos os
tenants. Registro por `PUT/GET/DELETE /v1/webhook-endpoints/{tenantId}`, protegido por `X-Admin-Key`
(`AdminApiKeyFilter` do pacote `webhook.web` — cópia deliberada do filtro da risk-engine; serviços
separados, e mover para o `commons` arrastaria dependência de web). URL validada no domínio: http(s)
absoluto e TLS obrigatório fora de host local.

Segredo HMAC por tenant (V005): cada registro nasce com segredo próprio (`SecureRandom`, 32 bytes),
devolvido **uma vez** no `PUT` e no `POST /v1/webhook-endpoints/{tenantId}/rotate-secret` — o
`GET`/lista só mostram `secretConfigured`. Atualizar a URL preserva o segredo; rotação mantém o
anterior válido por `barrier.webhook.secret-rotation-overlap` (24h) e, durante a janela, a entrega
leva duas assinaturas (`X-Barrier-Signature` + `X-Barrier-Signature-Previous`). `barrier.webhook.secret`
global vira fallback de dev. O segredo fica em texto na coluna (assinar exige o valor) — criptografia
em repouso é Fase 6.

Fase B (mapeada, parcial): KYB de 1º grau já ativo (ver acima); pendentes: monitoramento
contínuo, reavaliação periódica, recálculo por transação, navegação do QSA até 3º grau (provedor
KYB dedicado), e registro multi-tenant de endpoints.

Cadastro de PF vindo do bureau: `PersonProfile` (identity/domain) é o simétrico do `CompanyProfile`
— nascimento, nacionalidade e endereço saem do bureau e o `AssessmentProcessor` os persiste no
`SubjectProfile` por patch (campo ausente preserva o que o parceiro declarou). Ocupação continua
sendo declaração do cliente: bureau nenhum a fornece. O `FakeCpfBureauProvider` devolve o perfil
completo, e o cenário `9998…` responde SEM cadastro para o gate da CMN 4.753 continuar exercitável.

Rastro da consulta ao bureau (V031): `identity_checks.provider_reference` (o `QueryId` da
BigDataCorp; nulo quando a fonte não fornece) e `raw_response` JSONB com **redação** de
`MotherName` — ver `BureauTrace`. Desligável em `barrier.identity.store-raw-response`. ⚠️ dado
pessoal: retenção e criptografia em repouso são pendências da Fase 6.

Status `SOLICITAR_DOCUMENTO`: risco aprovado + cadastro incompleto sai da fila de EDD (não é
reprovação — reprovar por falta de dado mentiria na trilha e contaminaria a taxa de recusa).
`Assessment.decide` segue exigindo `EM_REVISAO`.

Cadastro (CMN 4.753, ADR-0012): `SubjectProfile` (pacote `subject.profile`) é o cadastro
completo do subject — **um por (subject, tenant declarante)**
(`subject_profiles UNIQUE (subject_id, tenant_id)`, V024), separado do `Subject` (que
continua sendo só a identidade mínima para dedup e segue global). O dossiê **não** é
compartilhado entre parceiros: cadastro global era lido e escrito por qualquer tenant com
vínculo — e vínculo nasce de um `POST` —, o que vazava o dossiê do cliente alheio e permitia
induzir aprovação automática completando o cadastro de outro. Por isso nem
`SubjectProfileRepository` nem `SubjectProfileService` têm assinatura que aceite só o
`subjectId`: o tipo do método é a defesa. Enriquecimento pelo bureau grava sob o tenant da
avaliação. Campos nullable divididos por tipo (PF:
`birthDate`/`nationality`/`occupation`; PJ: `foundingDate`/`cnaeCode`/`shareCapital`/
`legalRepresentative*`/`partners`); `partners` serializado em `partners_json` (mesmo padrão de
`hits_json`/`results_json`). Cadastro é progressivo: `PUT /v1/subjects/{document}/profile`
aceita atualização parcial a qualquer momento (`SubjectProfilePatch.applyTo` mescla, campo nulo
preserva o existente). `RegistrationCompleteness.evaluate(documentType, profile)` é o checklist
mínimo por tipo — `AssessmentProcessor` consulta depois do score de risco e rebaixa
`APROVADO` → `EM_REVISAO` (com fator explicando os campos faltantes) se o cadastro estiver
incompleto; reaproveita o workflow humano de decisão já existente, sem status novo. Os dados
objetivos de PJ do bureau (`CompanyProfile` — antes descartados) agora são persistidos no
`SubjectProfile` assim que `IdentityService.verify` retorna.

Watchlist em produção (ADR-0013): `WatchlistReadinessGuard` (`ApplicationRunner`) falha o
startup (`IllegalStateException`) se o profile `prod` estiver ativo e a única fonte de
watchlist presente for a `SEED` — evita subir silenciosamente em produção sem CGU/OFAC
habilitados. `application-prod.yml` já habilita `barrier.watchlist.cgu.enabled` e
`.ofac.enabled` por padrão nesse profile.

Bureau simulado de CPF: `FakeCpfBureauProvider` (`@Order(100)`, `@Profile("!prod")`,
`authoritative() == false`) substituiu o stub que aprovava tudo. Qualquer CPF válido é atendido;
CPF comum é `REGULAR`, e prefixo `999` + dígito seletor escolhe o cenário (falecido, suspensa,
nula, indisponível...). Tabela em `docs/implementation/bureau-simulado.md`. Não serve de fallback
para bureau real indisponível.

Situação cadastral do CPF (fecha o "a confirmar" do ADR-0014): `TaxIdStatus` + `HasObitIndication`
do `basic_data` decidem **antes** da comparação de nome. Titular falecido virou
`BureauResult.Outcome.DECEASED` → `IdentityStatus.DECEASED` → `IDENTITY_DECEASED` (1000, REJECT),
com desfecho próprio em vez de `NOT_FOUND` para a trilha não mentir. `NULA` → NOT_FOUND;
`SUSPENSA`/`CANCELADA`/`PENDENTE` → MISMATCH; status ausente → MISMATCH, nunca MATCH.

Bureau real de CPF (ADR-0014): `BigBoostBureauProvider` (`@Order(20)`, entre `BrasilApiBureauProvider`
`=10` e `FakeCpfBureauProvider` `=100`) chama o dataset `basic_data` da BigBoost/BigDataCorp
(`POST /pessoas`, headers `AccessToken`/`TokenId`) — self-service, sem CNPJ necessário para
contratar (ao contrário do Serpro). Desligado por padrão
(`barrier.identity.bigboost.enabled=false`); credenciais via `BIGBOOST_ACCESS_TOKEN`/
`BIGBOOST_TOKEN_ID`. `Result` vazio → NOT_FOUND; caso contrário a situação cadastral decide
primeiro (ver acima) e só CPF regular chega à comparação de nome.

Inidoneidade ≠ sanção (Onda 2): `MatchType.DEBARMENT` separa CEIS/CNEP de sanção financeira —
inidoneidade em licitação não impede relacionamento bancário e gerava `REJECT` automático.
`DebarmentMatchRule` (screening) + `DebarmentRiskRule` (risco, 200+REVIEW por documento no titular,
100 sem recomendação por nome, nunca REJECT, sócio não escala). **Não** é `RegulatoryRiskRule` — é
apetite de risco, desligável pelo registry (V030). Consequência: a CGU não conta mais como cobertura
de `SANCTION`; a única fonte é a OFAC, então habilitar só a CGU em prod falha o
`WatchlistReadinessGuard`. `ENGINE_VERSION` = `barrier-risk-rules/1.8.0`.

Bureau real de CNPJ (Onda 2): `BigBoostCnpjBureauProvider` (`@Order(20)`, dataset `basic_data` da
API de Empresas, mesma flag `barrier.identity.bigboost.enabled`) tira a cadeia de PJ do fail-open —
BrasilAPI fora do ar agora cai em outro bureau real, não no simulado. `CnpjBureauReadinessGuard`
barra a subida em `prod` sem provider autoritativo de CNPJ (ou com base-url local) e **avisa**
quando a BrasilAPI é a única fonte de PJ (API pública sem SLA sustentando controle regulatório).
`barrier.identity.brasilapi.enabled` permite desligá-la; `application-prod.yml` liga a BigBoost.
⚠️ `basic_data` de empresas **não traz QSA** — perfil vem com abertura/CNAE e sócios vazios, então
`CorporateStructureRiskRule` fica sem entrada quando este provider atende; e o schema ainda não foi
verificado contra a API real. Ver `CorporateStructureCoverageRiskRule`, abaixo, para o guard que
fecha o silêncio disso.

Guard de cobertura de QSA (branch `feat/kyb-coverage-gaps`): mesmo modo de falha que o projeto já
fechou para watchlist — importação/bureau falha → dado vazio → CLEAR → todos aprovados, com trilha
"limpa" — reaparecendo na estrutura societária. Quando a BigBoost atende sem QSA,
`CorporateStructureRiskRule` fica sem entrada (nenhum sócio para achar estrangeiro/PJ) e o
screening de partes relacionadas roda sobre lista vazia (nenhum sócio conferido contra
OFAC/CSNU/PEP) — nada registrava isso, e a avaliação concluía APROVADO.
`CorporateStructureCoverageRiskRule` força REVIEW quando o bureau confirma a PJ
(`CompanyProfile != null`) mas `partners()` vem vazio; a evidência cita o bureau que atendeu
(`IdentityCheck.provider()`), para o analista distinguir limite de fonte de empresa sem sócio.
**Regulatória** (entra em `RegulatoryRiskRules`, migration V039) — diferente do
`CorporateStructureRiskRule`, que pontua sinais *dentro* de um QSA existente e é apetite: esta
regra detecta a *ausência* do QSA, o mesmo tipo de gap que `ScreeningCoverageRiskRule` fecha para
listas. Não dá para distinguir "bureau sem QSA" de "empresa legitimamente sem sócio"
(MEI/empresário individual) com o dado disponível hoje — `CompanyProfile` não carrega natureza
jurídica/porte, e nenhum provider de CNPJ expõe isso; a regra é fail-closed de propósito,
registrado no Javadoc.

`ADVERSE_MEDIA` na exigência de cobertura, **condicional** (mesma branch, corrigida após rodar a
suíte completa): a primeira versão desta mudança fez `ScreeningCoverageRiskRule.REQUIRED` incluir
`MatchType.ADVERSE_MEDIA` incondicionalmente, igual a `SANCTION`/`PEP` — e quebrou pior do que o
fail-open que existia para fechar. `ADVERSE_MEDIA` nunca é populada em `WatchlistImportStatus`:
mídia negativa é `NegativeMediaProvider`, consultado **ao vivo** por avaliação, não importado como
`WatchlistSource`. Sem cobertura possível de existir, a regra pontuava **100% das avaliações**,
recriando o problema que motivou o `SOLICITAR_DOCUMENTO` (7501 de 7529 avaliações em
`EM_REVISAO` por ruído de cadastro, cegando operações — ver `plano-remediacao-auditoria.md`).
Corrigido no mesmo padrão de `BureauProvider.authoritative()`: `NegativeMediaProvider` ganhou
`authoritative()` (default `true`), `StubNegativeMediaProvider` sobrescreve para `false`.
`ScreeningCoverageRiskRule` passou a receber `List<NegativeMediaProvider>` (construtor de 1
argumento preservado como conveniência = "nenhum provedor", para não quebrar quem constrói a
regra manualmente sem mídia negativa) e só exige `ADVERSE_MEDIA` quando existe pelo menos um
provider autoritativo na lista — hoje, sem contrato, isso nunca acontece e a regra não pontua por
mídia negativa; contratado um provider real, a exigência entra como sanção e PEP (controle que
deveria estar rodando e não está confirmado). `WatchlistReadinessGuard` **não** ganhou a mesma
exigência incondicional: adicionar `ADVERSE_MEDIA` à lista que barra a subida em `prod` derrubaria
a aplicação inteira por falta de um provedor que hoje ninguém contratou — mais forte que o
problema justifica. O guard só **avisa** quando falta cobertura de mídia negativa, no mesmo padrão
do `CnpjBureauReadinessGuard` para a BrasilAPI como único bureau de PJ (o aviso não é
condicionado a `authoritative()` — é aviso de startup, sempre útil). `DEBARMENT` segue de fora da
exigência de cobertura, de propósito — é apetite de risco (ver acima), não obrigação regulatória.
`ENGINE_VERSION` = `barrier-risk-rules/1.8.0`.

Registry de regras de risco: `RiskRule.code()` é o código estável da família da regra
(`NEW_COMPANY`, `SANCTION` etc.) — independente do `ruleCode` granular que `RiskResult` pode
variar por desfecho (ex.: `IdentityRiskRule` devolve `IDENTITY_NOT_FOUND`/`IDENTITY_MISMATCH`/
`IDENTITY_UNAVAILABLE`, mas a família é `IDENTITY`). `risk_rule_registry` (migration V016)
guarda o estado operacional de cada família — `enabled`, vigência (`valid_from`/`valid_until`)
e `criticality` (INFO/ALERT/REVIEW/BLOCK, informativa) — editável sem deploy via
`PUT`/`GET /v1/risk-rules` (`RiskRuleRegistryController`). `RiskRuleRegistryService.isActive`
é fail-open: regra sem linha no registry fica ativa (o registry é kill switch/vigência, não
allowlist). `RiskScoringService` filtra as regras pelo registry antes de avaliar — diferente do
override por tenant (`tenant_risk_config`, que ajusta parâmetro de uma regra já ativa por
parceiro), isto liga/desliga a regra inteira, globalmente, para todos os tenants.

Regras de risco configuráveis por tenant: `tenant_risk_config` (migration V015, chave composta
`tenant_id`/`rule_code`/`param_key`) guarda overrides por parceiro; `TenantRiskConfigService`
(pacote `tenant.config.service`) lê o override ou cai no default global (o mesmo `@Value` de
antes). Só regras de apetite de risco — não regulatórias — são configuráveis:
`NewCompanyRiskRule` (`months`/`score`) e `SensitiveCnaeRiskRule` (`cnae-codes`, que só é
**unido** ao default, nunca substituído/`score`). Bandas de score, `IdentityRiskRule`,
`PepRiskRule`/`SanctionRiskRule` (risco) e `PepMatchRule`/`SanctionMatchRule` (screening)
continuam fixas — ArchUnit (`regras_fixas_nao_dependem_de_config_por_tenant`) barra essas
classes de depender de `TenantRiskConfigService`. `RiskContext` ganhou `tenantId`
(`AssessmentProcessor` preenche a partir de `Assessment.tenantId()`); o parâmetro efetivamente
usado por uma regra disparada entra na evidência (`config:months=`/`config:score=`) —
`ENGINE_VERSION` continua só sobre código/algoritmo, não sobre config de tenant. Gestão via
`PUT`/`GET /v1/tenants/{tenantId}/risk-config` (`TenantRiskConfigController`), validado por
`TenantRiskConfigValidator` (allowlist de `rule_code`/`param_key` + ranges); é operação
interna/admin, não self-service do parceiro — deixar o próprio tenant relaxar seus controles é
o risco que a validação existe para evitar (sem gate de admin-auth dedicado ainda, mesma
pré-auth por header do resto da API). Decisão de não separar isso (nem o cadastro PF/PJ já
existente) em serviço novo: ADR-0009 (monólito modular, split incremental por gatilho real).

Mídia negativa: `MatchType.ADVERSE_MEDIA` (já existia, sem uso) ganhou a cadeia completa —
`NegativeMediaProvider` (marca de `WatchlistProvider`, mesma interface de busca) +
`StubNegativeMediaProvider` (casa nome contra CSV `barrier.negative-media.flagged-names`, vazio
por padrão — sem falso positivo em dev; troca por BigBoost/LexisNexis/Dow Jones é só nova
implementação da interface) + `AdverseMediaMatchRule` (screening, filtra o apontamento, mesmo
padrão de `PepMatchRule`/`SanctionMatchRule`) + `NegativeMediaRiskRule` (risco, força REVIEW
como PEP — apontamento de mídia pode ser homônimo/desatualizado, exige julgamento de analista
antes de reprovar). Nenhuma mudança em `ScreeningService`/`RiskScoringService`: os dois já
agregam qualquer bean das interfaces `WatchlistProvider`/`RiskRule`.

PEP e cobertura de listas (branch `feat/pep-watchlist-cgu`): `PepWatchlistSource` (segmento `pep`
do Portal da Transparência) é a **primeira fonte a produzir `MatchType.PEP`** — antes CEIS/CNEP/OFAC
eram todas `SANCTION` e a `PepRiskRule` nunca disparava em produção, apesar do Javadoc citar a
Circular 3.978. A CGU publica o CPF **mascarado** (`***.123.456-**`), então: `document` fica nulo
(match exato erraria o titular) e os 6 dígitos centrais vão para `document_partial` (migration
V019), que o `FuzzyNameWatchlistProvider` usa como **discriminador** do match por nome — sem ele,
todo homônimo da lista viraria revisão manual. ⚠️ O formato do CSV **não foi verificado contra o
portal real** (403 do ambiente de dev, inclusive para `ceis`); os rótulos de coluna são resolvidos
por alternativas, mas validar antes de produção.

Cobertura de screening: `WatchlistImportStatus` guarda o resultado da última importação por fonte
(em memória — o que importa é se *esta* instância tem cobertura utilizável); `WatchlistImporter`
recusa substituir a base por uma importação **vazia** (CSV com layout novo apagaria a lista inteira);
`WatchlistHealthIndicator` derruba `/actuator/health`; `ScreeningCoverageRiskRule` força REVIEW
quando falta cobertura de SANCTION ou PEP. Junto, fecham o modo de falha em que importação falha →
tabela vazia → screening CLEAR → todos aprovados, com health verde. `WatchlistReadinessGuard` agora
exige cobertura das duas categorias em prod (via `WatchlistSource.provides()`), não só "alguma fonte
além da SEED".

Consistência cadastral: `RiskContext` ganhou `profile` (o `SubjectProfile` do subject, buscado
por `SubjectProfileService.find` — novo método, cadastro em branco se não houver nenhum dado
ainda); `AssessmentProcessor` busca uma vez e reaproveita tanto no `RiskContext` quanto no gate
de completude (`RegistrationCompleteness.evaluate` direto, sem outra query). `ConsistencyRiskRule`
(código `PHONE_ADDRESS_MISMATCH`) compara o DDD do telefone com o estado do endereço via
`PhoneAreaCode` (tabela DDD→UF do plano ANATEL) — sinal barato, não depende de provedor externo;
não força recomendação (mudança/portabilidade é comum), só soma ao score. Nome divergente/CPF de
outro titular já eram cobertos por `IdentityRiskRule` (`IDENTITY_MISMATCH`), não duplicado aqui.

Idempotência do intake: `POST /v1/assessments` aceita o header opcional `Idempotency-Key`
(escopo por tenant, tabela `idempotency_keys` V029, janela `barrier.assessment.idempotency-window`
= 24h). Mesma chave + mesmo conteúdo devolve a avaliação original com `Idempotency-Replayed: true`;
mesma chave + conteúdo diferente = 409; sem header, cada POST cria uma avaliação (comportamento
anterior). A comparação é por hash SHA-256 (tenant|tipo|documento normalizado|nome) — sem PII
duplicada na tabela. `IdempotencyService` roda em `REQUIRES_NEW`: a reserva precisa estar commitada
antes da avaliação (é o índice único que serializa requisições concorrentes) e a liberação precisa
sobreviver ao rollback da submissão que falhou. `AssessmentService.submit` devolve
`SubmissionResult(assessment, replayed)`.

Monitoramento contínuo (Circular 3.978): `RescreeningService` (pacote `rescreening`) reavalia
clientes quando uma lista passa a apontá-los. O gatilho é o **delta** da importação
(`WatchlistDelta`, calculado dentro do `replaceSource`); casa por documento e por nome (OFAC/CSNU
não publicam documento), com o mesmo limiar do screening. Reavaliar = submeter avaliação nova pelo
pipeline normal, com `origin = RESCREENING` e `origin_detail = fonte@versão` (V032) — não há
caminho paralelo de decisão. Travas: importação sobre base vazia é linha de base e não dispara;
teto `barrier.rescreening.max-subjects-per-import` aborta e grita (delta gigante = fonte que mudou
de layout); uma avaliação por (subject, tenant) por importação. Desligável em
`barrier.rescreening.enabled`.

Documentoscopia e biometria (ADR-0016, etapa 3): `AssuranceService` (pacote `assurance`) prova
que quem está do outro lado é o titular — o motor até aqui confirmava CPF regular e nome batendo,
não a pessoa. Guarda o **resultado**, nunca a imagem: `AssuranceCheck` (V035) fica com desfecho,
score, provedor e referência da consulta, mesmo padrão de `BureauTrace`. Consentimento é exigido
por verificação, na assinatura do serviço (`AssuranceConsent`, colunas em V036) — ausência recusa
com 400 antes de acionar o provedor, nunca depois. Os campos que a documentoscopia extrai são
comparados contra o cadastro (CMN 4.753) e o `Subject`: nascimento que confere vira
`FieldVerification` com `method=DOCUMENT` (mesmo padrão de `recordBirthDateFromBureau`); nascimento
que diverge vira `AssuranceCheck.divergences()` (V037) — sinal de possível fraude, não campo
faltando. Nome é diferente: pertence ao `Subject`, não ao cadastro, então não tem campo verificável
equivalente **para ele** — divergir também vira `divergences()`, mas nunca `FieldVerification`.
`IdentityAssuranceRiskRule` soma a divergência ao score e cita **quais campos** divergiram na
evidência (NAME/BIRTH_DATE, nunca o valor — fator explicável). Qualquer desfecho, inclusive FAIL/INCONCLUSIVE/UNAVAILABLE, dispara reavaliação automática:
`AssuranceService` agenda a notificação em `TransactionSynchronization.afterCommit()` (a gravação
do check precisa estar commitada antes de reagir) e o listener roda em `REQUIRES_NEW` — sem
propagation própria ele entraria na transação já commitada em vez de abrir uma nova, e a
reavaliação sumiria sem lançar. A reação mora em `rescreening`
(`AssuranceReassessmentTrigger`), não em `assurance`: `assurance` declara a interface
(`AssuranceRecordedListener`) sem saber quem a implementa, porque reavaliar chamando
`AssessmentService` direto de dentro de `assurance` fecharia o ciclo
`assurance → assessment → risk → assurance` (risk já depende de assurance via
`IdentityAssuranceRiskRule`) — o mesmo padrão de inversão de dependência do
`WatchlistImportListener`. `ArchUnit` (`sem_ciclos_entre_modulos`) é o que prova que a inversão
segura. Kill switch `barrier.assurance.enabled` (default `true`, mesmo padrão de
`barrier.rescreening.enabled`): desligado, o endpoint recusa com 409 antes de acionar qualquer
provedor. Em `prod`, sem provedor real contratado, `UnavailableDocumentVerificationProvider`/
`UnavailableBiometricVerificationProvider` (`@Profile("prod")`) devolvem sempre `UNAVAILABLE` —
os stubs são `@Profile("!prod")` e, sem esses providers de emergência, o construtor obrigatório
de `AssuranceService` faria o contexto inteiro falhar na subida em produção;
`AssuranceProviderReadinessGuard` só **avisa** quando são eles que estão ativos, no padrão de
`CnpjBureauReadinessGuard`.

**Decisão de produto (2026-08-13): documentoscopia aprovada é pré-requisito da biometria.**
`documentFaceReference` tinha `@NotBlank` — exigia uma string, não verificava nada; `"x"` passava.
Era campo obrigatório, não pré-requisito, o mesmo padrão de falha (controle que roda e não
verifica) do `RegistrationCompleteness`/`PepRiskRule` citados em `plano-remediacao-auditoria.md`.
Agora `AssuranceService.verifyBiometrics` exige um `AssuranceCheck` de `kind = DOCUMENT` com
`outcome = PASS` para o `(subjectId, tenantId)` antes de acionar o provedor de biometria (chamada
paga); sem ele, `DocumentGateNotSatisfiedException` recusa com 409 — exceção própria, não
`IllegalStateException`, para o parceiro distinguir "assurance desligado" de "falta
documentoscopia". Só `PASS` libera: comparar rosto contra documento que não passou na
autenticidade prova pouco, então `FAIL`/`INCONCLUSIVE`/`UNAVAILABLE` recusam do mesmo jeito que
ausência total. Consequência operacional: provedor de documentoscopia indisponível trava a frente
inteira, não só metade (o cliente não fica preso — `IdentityAssuranceRiskRule` converte
`UNAVAILABLE` em revisão humana — mas não avança sozinho). Muda o contrato de integração (ordem
passa a ser obrigatória); viável agora porque o endpoint ainda não está em produção. Fora de
escopo, decisões de produto separadas: validade temporal do `PASS` (hoje sem janela) e
correspondência do `documentFaceReference` com a referência do check que autorizou.

Throttle da frente de assurance (branch `feat/assurance-throttle`): duas travas fecham o que faltava
depois de ligar o gatilho de reavaliação. **Dedup por janela** —
`barrier.assurance.reassessment-window` (default `PT5M`) — no máximo uma reavaliação por
`(subject, tenant)` a cada janela; `AssuranceReassessmentTrigger` consulta
`AssessmentService.existsRecentByOriginAndSubject` (avaliações com `origin = ASSURANCE` para
aquele par, dentro da janela — a checagem mora no `AssessmentService`, não no repositório
exposto direto a outro módulo: o service é o portão do módulo) antes de submeter, mesmo
vocabulário do "uma avaliação por (subject, tenant) por importação" do `RescreeningService`. Vinte tentativas de biometria em
sequência viravam vinte avaliações completas — vinte consultas pagas à BigBoost e vinte rodadas
de screening pelo mesmo evento; agora só a primeira dentro da janela dispara. **A submissão dentro
da janela continua gravando o `AssuranceCheck` normalmente** — só a reavaliação é suprimida (com
log): a trilha de tentativas é o próprio sinal de fraude que a regra abaixo conta, e perdê-la
seria pior que o problema que a janela resolve. **Janela na contagem de tentativas** —
`barrier.assurance.attempts-window` (default `PT24H`) — `AssuranceService.attempts` deixou de
contar o histórico inteiro (`repository.findAll(...).stream().count()`, materializando tudo no
caminho quente de toda avaliação) e passou a um `COUNT(*)` no banco
(`AssuranceCheckRepository.countRecent`) com a janela no `WHERE`; sem isso, três tentativas ao
longo de anos (re-KYC periódico, troca de aparelho) travavam o cliente em `+200/REVIEW` para
sempre, sem caminho de saída — o sinal que a regra quer capturar é fenômeno de sessão, não de
vida inteira.

**Validação cadastral (Datavalid/Serpro `pessoa-fisica/validacao`, 2026-08-13).** Diferente de
documentoscopia (lê um documento) e biometria (prova presença): confere dado **declarado** contra
RFB e, só para endereço, contra a base da CNH (SENATRAN) — não é documentoscopia, é veracidade
cadastral. Vive em `subject.profile`, não em `assurance` — o consumidor natural é o
`FieldVerificationService` que já existia (OTP/BUREAU/DOCUMENT). `subject.profile` não pode
depender de `assurance` (regra de módulo), mas as duas frentes usam a mesma credencial/token
Serpro; o plumbing de autenticação (`SerproTokenClient`, o `RestClient` do `datavalid/v5`) mudou
de `assurance.client.serpro` para um pacote neutro, `com.barrier.riskengine.serpro`
(`SerproGatewayConfig`) — não em `commons`, para não arrastar dependência de `RestClient` nele
(mesmo raciocínio já registrado aqui para o `AdminApiKeyFilter`). Nascimento confirmado pela RFB
vira `FieldVerification` com `method = REGISTRY` (novo valor do enum — fonte diferente do bureau
comercial e da documentoscopia, força de prova distinta numa contestação). Endereço confirmado
usa `method = ADDRESS_LOOKUP`, que já existia no enum definido para "base de endereçamento" e
nunca tinha sido usado. **Cobertura de endereço é parcial**: só fecha para quem tem CNH com
endereço registrado — sem CNH, o campo continua sem verificação (por isso o item correspondente
em `plano-remediacao-auditoria.md` segue aberto, não marcado como concluído). Gating no mesmo
padrão da biometria: `barrier.serpro.enabled` liga a conectividade compartilhada,
`barrier.registry-validation.enabled` liga esta frente especificamente; `privacidade`
(`id_template` da RFB, `token`/`cnpj_anuente` da SENATRAN) é config de contrato, não segredo
técnico — a família de erro `DV200–DV213` (template mal configurado) vira log apontando para
configuração, nunca para o CPF do cliente. `DV001` (menor de idade) é desfecho cobrado, não erro
de transporte. Não verificado ao vivo: o ambiente de execução desta etapa não teve egress de rede
para o gateway Serpro (diferente da etapa de biometria, que rodou contra a demonstração real);
todo o mapeamento de contrato segue a documentação oficial verbatim e a taxonomia de erro segue
por analogia com a já sondada.

**Reuso de verificação de identidade (branch `feat/identity-reuse`).** Parar de pagar a mesma
consulta de bureau duas vezes: `IdentityService.verify` consulta `identity_checks` antes de sair
para a rede e, havendo um check elegível — mesmo tenant, mesmo documento, mesmo nome normalizado,
desfecho definitivo, dentro do TTL —, grava um check **novo** copiando o desfecho e apontando
para o original em `reused_from_id` (migration V040; o plano cita V036, desatualizado). Cada
avaliação continua com seu próprio `identity_check` — `RiskScore.identityCheckId` segue
identificando exatamente a verificação que sustentou aquela decisão (garantia da V028). **Só
CPF**: `IdentityResult.company` (o `CompanyProfile` com QSA/CNAE/abertura) é transiente e não é
persistido no check — reusar um check de PJ devolveria `company == null` e a
`CorporateStructureRiskRule` pararia de disparar em silêncio, o mesmo fail-open que a auditoria
mandou eliminar; reidratar o perfil do `raw_response` é entrega própria, fora de escopo.
`UNAVAILABLE` nunca é reusado — congelaria uma indisponibilidade passada como resposta. Escopo do
reuso é o **tenant**: reuso entre tenants repetiria o erro que o ADR-0012 corrigiu no cadastro,
fica como opt-in futuro com ADR próprio. Desligado por padrão
(`barrier.identity.reuse.enabled=false`), TTL `barrier.identity.reuse.ttl` (`PT24H`). Contadores
`barrier.identity.check{outcome="fresh"|"reused"}` separam economia de custo de queda de tráfego
— sem eles, uma flag ligada por engano numa base grande não apareceria em lugar nenhum;
`UNAVAILABLE` não conta em nenhum dos dois, porque não houve verificação. A procedência
(`identityReused`/`identityCheckedAt`, este seguindo `reused_from_id` até a consulta que de fato
foi à rede) aparece tanto no `GET /v1/assessments/{id}` quanto no evento
`barrier.assessment.completed` (`AssessmentCompletedPayload`) — o parceiro recebe o desfecho pelo
webhook, não busca o `GET`, e uma decisão apoiada em verificação de ontem é informação que ele
precisa para a própria trilha. Campo novo em JSON é retrocompatível e a Webhook API não
desserializa o payload em tipo estrito (repassa como string opaca), então nada mudou nela.
**Interação com o rescreening:** `RescreeningService` submete avaliação nova para o mesmo
`(subject, tenant)` quando uma lista passa a apontar o cliente. Com TTL de 24h e reuso ligado, um
rescreening disparado logo depois **vai reaproveitar** a verificação de identidade da avaliação
anterior — é defensável (o que mudou foi a lista de sanções, não o titular), mas contraintuitivo:
o rescreening existe porque fatos mudam, e reaproveitar a identidade não é esquecer disso, é
reconhecer que *esse* fato (quem é o titular) não é o que mudou. **Reuso não substitui cota**:
reprocessar 500 mil documentos distintos continua custando o mesmo (ver
`plano-remediacao-auditoria.md`, Onda 3) — reuso ataca repetição, cota ataca volume.

**Risco corrente do cliente (fila-origem F3/F4, módulo `riskstate`).** `risk_scores` continua sendo
a trilha imutável (uma linha por avaliação, nunca sobrescrita); ao lado dela entra a projeção viva
`subject_risk_state` (V041), que responde "qual é o risco deste cliente agora" — sem ela era preciso
caçar a última avaliação concluída, e nada no código fazia isso. Chave **(subject_id, tenant_id)**,
não `subject_id`: a decisão é por tenant no assessment (ADR-0011/0012), o mesmo cliente pode estar
APROVADO num parceiro e REPROVADO em outro, e projeção global vazaria risco entre parceiros. O
upsert é **monotônico no `evaluated_at` da avaliação**, não na ordem de commit — rescreening,
assurance e decisão manual concluem fora de ordem, e uma avaliação iniciada antes e concluída depois
não pode enterrar um estado mais novo (`SubjectRiskState.supersededBy`); empate preserva o gravado.
A gravação é na **mesma transação** da conclusão: é projeção, não evento. Decisão humana também
atualiza (`recordManualDecision`, usando `reviewedAt` como relógio — com `completedAt` ela empataria
com a decisão do motor e seria descartada), preservando score e `engine_version` do motor: o
analista muda o desfecho, não o nível. `GET /v1/subjects/{document}/risk-state`, escopado por tenant
(404 sem vínculo), com fallback pela última avaliação concluída — nele `riskScore` volta **null**, e
não 0, porque 0 é score válido e o mais favorável que existe; `fromProjection` diz de onde veio.
**Por que módulo próprio e não `subject.state`:** o ArchUnit (`sem_ciclos_entre_modulos`) rejeitou as
duas primeiras tentativas — `assessment → subject.state → assessment` e, via `RiskLevel`,
`risk → subject.state → risk` (risk já depende de subject pelo `SubjectProfile` no `RiskContext`).
A ligação com o pipeline é por **inversão**: `AssessmentCompletedListener` declarada em `assessment`
(que não sabe quem reage) e implementada por `SubjectRiskStateProjector` — mesmo padrão do
`AssuranceRecordedListener`. Mudança de nível emite `barrier.subject.risk_level_changed` pela outbox,
na mesma transação da projeção; primeira avaliação (`null → LOW`) **não** emite (não é "o risco
mudou", é "o cliente passou a existir", e o `assessment.completed` já cobriu), nível repetido também
não. O payload leva `origin` (ONBOARDING/RESCREENING/ASSURANCE) e `worsened` — a política de
notificação é do parceiro, filtrar aqui seria decidir por ele, e `worsened` evita que ele
reimplemente a escala (a do Barrier é maior = pior, invertida em relação ao Origem). O agregado do
envelope continua sendo o **assessmentId**, não o subject: o campo do `EventEnvelope` chama-se assim
e vira `deliveries.assessment_id`; partição por documento é mudança deliberada do contrato (F8), não
efeito colateral. A Webhook API consome o tópico novo no **mesmo listener e mesmo consumer-group**
(a máquina de entrega é a mesma). Esse evento **não tem reconciliação**, de propósito e registrado
no ADR-0017: é aviso sobre estado consultável (`GET .../risk-state`), não o registro único de um
fato — diferente da decisão de KYC, que tem.

**Alertas com baseline móvel (fila-origem F5, módulo `monitoring`).** Fecha o "afoga em silêncio"
que o teste de carga do ADR-0015 expôs: 69.809 avaliações presas em `EM_ANALISE`, sem erro, sem
latência ruim, sem alerta — `PipelineHealthMetrics` media, e nada comparava a medida contra nada.
`AlertEvaluator` (`@Scheduled`, gated por `barrier.monitoring.alerts.enabled`, ligado em
`application-prod.yml`) monta **um** snapshot por ciclo e submete a todas as `AlertRule` (Strategy,
como `RiskRule` — alerta novo = bean novo); uma query por regra viraria carga sobre a tabela mais
quente. Quatro regras: `backlog_analise` (limiar **fixo**, de propósito — se a fila normalmente
demora 6h, o baseline aprenderia que 6h é normal e pararia de avisar), `vol_hora_baixo` (parceiro
que parou de mandar: não gera erro nenhum, o sistema fica *melhor* por todo indicador técnico
enquanto o produto está parado), `aprov_auto_alto`/`baixo` (regra desligada no registry, provider
devolvendo vazio — o fail-open que `ScreeningCoverageRiskRule`/`CorporateStructureCoverageRiskRule`
já tiveram que fechar duas vezes — e fraude em escala, os três com a mesma assinatura) e
`recusa_alta`. **A comparação é sempre contra a mesma janela de 60 minutos em dias anteriores**
(`barrier.monitoring.history-days`, 7): janela deslizante, não hora do relógio — com hora cheia, às
14h05 o observado seria 5 minutos contra 60 históricos e `vol_hora_baixo` dispararia no início de
toda hora. O Origem normaliza pela fração do período decorrida; medir sempre 60 minutos elimina o
viés na origem. Três travas contra alerta que mente: `Baseline.MIN_SAMPLES` (3) cala o avaliador em
instalação nova, `min-completed` (20) evita taxa sobre amostra pequena (3 conclusões viram 0%, 33%
ou 100% sozinhas), e janela histórica **sem conclusão** é descartada em vez de contar como 0% (senão
madrugada parada rebaixa a expectativa até o alerta nunca disparar). Dedup por código
(`repeat-interval`, 1h) — fila represada por 3h com ciclo de 5min mandaria 36 mensagens idênticas.
`AlertNotifier` é interface; o padrão só loga, para ligar o monitoramento não depender de contratar
canal. Alerta nunca carrega documento nem nome: descreve agregados, e é isso que permite mandá-lo
para canal com controle de acesso mais fraco que o do banco.

**Política de reavaliação (fila-origem F6, [ADR-0019](docs/adr/0019-politica-de-reavaliacao.md)).**
As travas que existiam no `RescreeningService` são todas contra *avalanche de importação*; nenhuma
respondia "quando reavaliar um cliente é legítimo". `ReassessmentPolicy` (pacote
`rescreening.policy`) exige **gatilho + alteração material + intervalo mínimo** por nível corrente
(LOW 1095 dias · MEDIUM 730 · HIGH 365 · CRITICAL 183 · **sem projeção 183**, fail-safe pelo pior
caso — desconhecido não é sinônimo de bom). O nível vem de `subject_risk_state` (F3), e a "última
decisão" é o `evaluatedAt` da projeção. ⚠️ **Intervalo mínimo NÃO se aplica a fato adverso novo**:
`WATCHLIST_DELTA` e `ASSURANCE` fazem **bypass**, porque entrada nova em lista de sanção é fato novo
e suprimi-la por "já reavaliei há 30 dias" descumpre a Circular 3.978 — custo de rescreening se
controla pelo reuso de identidade (V040), nunca deixando de reavaliar. O bypass é **propriedade do
enum** `ReassessmentTrigger`, não convenção de código, com teste dedicado que quebra o build se
mudar. `reassessment_decisions` (V042) grava **toda** passagem, inclusive o "não" com motivo
(`intervalo_minimo`/`sem_alteracao_material`): sem ela, rescreening que não gerou avaliação era
indistinguível de rescreening que nunca rodou — a mesma distinção entre *rodou e passou* e *estava
desligada* que o motor de risco faz em toda regra. As travas antiavalanche continuam valendo, são
ortogonais.

**Decisão de produto (2026-08-15): patch cadastral reavalia o cliente.** `PROFILE_PATCH` exige
alteração material **e fura o intervalo mínimo** — com o intervalo valendo, cliente LOW só
reavaliaria em 1095 dias e a materialidade seria decorativa (o comportamento observável seria o de
antes da política). Logo **o freio deste gatilho é inteiramente a materialidade**:
`MaterialProfileChange.detect` compara campo a campo contra o cadastro atual e só considera os
campos que alguma regra lê ou que o `RegistrationCompleteness` exige (fora: `email` e
`cnaeDescription`); valor igual não conta, inclusive diferença só de caixa/espaço/escala decimal;
lista de sócios vazia é "não informado", não "zerei o QSA". Sem essa comparação, o `PUT` sendo
progressivo e mesclado, o parceiro que sincroniza cadastro em lote pagaria uma consulta de bureau
por cliente sem ter mudado nada. Avaliação nasce com `origin = PROFILE_PATCH` e `origin_detail` =
os campos que mudaram (nomes, **nunca** os valores). ⚠️ **O laço fechado junto:** o
`AssessmentProcessor` grava no mesmo cadastro o que o bureau devolve, no meio da avaliação — se
esse caminho notificasse, toda avaliação geraria outra, cada uma com consulta paga,
indefinidamente. Os caminhos foram separados **no tipo**: `SubjectProfileService.update` (parceiro,
notifica) e `enrichFromBureau` (bureau, não notifica), não um booleano — a diferença é grande
demais para se errar por omissão. Regra: *o parceiro declara, o bureau confirma; só a declaração é
fato novo*. A reação vive em `ProfilePatchReassessmentTrigger` (pacote `rescreening`), implementando
`SubjectProfileUpdatedListener` declarada em `subject.profile` — mesma inversão do
`AssuranceRecordedListener`, porque `subject → assessment → subject` seria ciclo. Dedup de `PT5M`
(`barrier.subject.profile.reassessment-window`) evita uma avaliação por tecla em formulário salvo
campo a campo.

**Mesa de análise (fila-origem F7, módulo `mesa`, V043).** Fecha o que o plano de remediação já
reconhecia: *"um `POST /decision` não é case management: sem fila, SLA, atribuição, anexos,
histórico"*. Duas tabelas próprias — `assessment_cases` (ciclo **operacional**: fila, responsável,
abertura/fechamento) e `assessment_actions` (append-only) —, **não** colunas em `assessments`: a
fronteira entre "o que o motor decidiu" e "o que a operação fez" é a que precisa ficar nítida numa
fiscalização, e o módulo da mesa não escreve na tabela do motor. Filas: `ANALISE_PADRAO`,
`ALCADA_RISCO`, `AGUARDANDO_PARCEIRO`. `MesaCaseRouter` implementa `AssessmentCompletedListener`
(mesma inversão da projeção de risco — `assessment → mesa → assessment` seria ciclo): `EM_REVISAO`
abre em `ANALISE_PADRAO`, `SOLICITAR_DOCUMENTO` abre em `AGUARDANDO_PARCEIRO` (esses casos existiam
e não tinham fila nenhuma — ninguém os contava nem os cobrava), desfecho final fecha o caso.
**Ações manuais são eventos, não só o desfecho**: é delas que o SLA é reconstruído, e guardar só a
decisão destruiria a informação de quanto tempo o caso passou esperando alguém de fora. **SLA
pausável** (`SlaClock`, função pura): pausa exige o **par** `DOCUMENT_REQUESTED` →
`DOCUMENT_RECEIVED`; pedido **sem** recebimento não vira desconto — é conservador de propósito,
porque descontar tudo após o último pedido daria à mesa um jeito trivial de zerar o próprio SLA
(pedir um documento e nunca fechar). Recebimento sem pedido é ignorado, pedido repetido não abre
segunda janela, pausas sobrepostas não somam duas vezes, e janela fora da vida do caso é recortada.
Vale a frase do Origem: *"só contamos espera que dá para provar: sem registro de saída e fora da
fila, o intervalo é descartado"*. **O SLA nunca é coluna** — é derivado da linha do tempo a cada
leitura: contador incremental se perde no primeiro reprocessamento e não é auditável depois.
`GET/POST /v1/mesa/...` (fila, timeline, assign, move, request/receive-document, notes); a decisão
em si **continua** no `POST /v1/assessments/{id}/decision`, não foi duplicada.

**Ingestão comportamental (fila-origem F8, módulo `behavior`, V044).** Fundação do monitoramento
pós-onboarding (item 7 da Fase 8 do `risk-engine-plan`): `behavior_events` é acervo de **fatos
imutáveis** — não há `update` nem `delete` na interface do repositório, e essa ausência é a defesa;
corrigir o passado destruiria a base sobre a qual uma decisão foi tomada, correção se faz com evento
novo. `POST /v1/behavior-events` responde **202** (o fato foi aceito, nada foi decidido a partir
dele), é **idempotente por `sourceEventId`** do parceiro (reprocessamento da fila dele contaria a
mesma transação duas vezes) e reenvio duplicado também responde 202 com `duplicate=true` — reenviar
precisa ser seguro e barato, senão o parceiro evita reenviar e perde fato de verdade. `occurredAt`
no futuro além de `barrier.behavior.max-future-skew` (PT5M) é corrigido para o recebimento e
logado: fato "do futuro" ficaria eternamente dentro de qualquer janela deslizante construída sobre
estes eventos. **Ingestão não dispara reavaliação** — o volume é de outra ordem (uma transação por
compra, não uma por onboarding), e ligar cada fato a uma avaliação completa faria do cliente mais
ativo o mais caro; as regras que lerão o acervo são entrega própria, e é lá que a política de
disparo será decidida. O que se importou do `tzofe` é o **modelo de ingestão**, nunca regra como
dado — expressão editável em runtime sacrificaria `ENGINE_VERSION` e a trilha reproduzível.
`barrier.behavior.recorded` é particionado por **`subjectId`**: o Origem usa o `document`, mas chave
de partição aparece em log de broker, métrica de lag e inspetor de tópico — lugares sem o controle
de acesso do banco —, e o `subjectId` é único por documento (ADR-0011), dando a mesma garantia de
ordem por entidade sem espalhar CPF pela observabilidade. O payload livre do parceiro **não** viaja
no evento: fica no acervo, e quem precisar dele lê a base.

**Catálogo de eventos (fila-origem F9).** O gatilho de P8 era "terceiro consumidor ou primeiro
payload que muda de forma"; `barrier.behavior.recorded` levou o barramento de um para três tópicos,
então [docs/architecture/event-catalog.md](docs/architecture/event-catalog.md) passou a existir —
normativo, com produtor, chave de partição, consumidores e payload de cada evento, mais as regras
comuns (outbox obrigatório, idempotência por `eventId`, consumer-group por consumidor, nada de PII
em chave de partição). **Schema registry segue não existindo, com critério escrito:** entra quando o
produtor deixar de ser único ou na primeira quebra real a coordenar entre times que não compilam
juntos — hoje o `commons` dá compatibilidade em tempo de compilação e registry seria cerimônia. O
catálogo é a mitigação, e só funciona se for atualizado no mesmo PR que muda o evento; desatualizado
é pior que inexistente, porque dá confiança falsa. ⚠️ Registrado lá: `EventEnvelope.assessmentId` é
o id do **agregado** de cada evento, não necessariamente uma avaliação — o nome é histórico e a
`deliveries.assessment_id` da Webhook API depende dele.

**Reavaliação periódica (re-KYC) — o gatilho que faltava.** `PeriodicReassessmentJob` (cron
`0 30 3 * * *`, madrugada porque o lote compete com o onboarding pelo mesmo pipeline global) varre
`subject_risk_state` e submete avaliação com `origin = PERIODIC_REVIEW` para quem venceu o intervalo
do seu nível. Até aqui a `ReassessmentPolicy` sabia decidir sobre `PERIODIC` e **nada a acionava**:
o intervalo por faixa era só *freio*, nunca *gatilho*, e a reavaliação periódica que o F3 existia
para destravar não acontecia. Risco aqui é **custo**, não corretude — cada cliente devido é uma
avaliação completa com consulta paga: **desligado por padrão**
(`barrier.rescreening.periodic.enabled`), **teto por execução** (`max-per-run`, 200 — ligar numa
base com anos de histórico tornaria devido um lote enorme de uma vez; o teto vira fila drenada ao
longo de dias, mais antigo primeiro) e **não empilha** (cliente com avaliação em análise é pulado,
porque ela vai concluir e atualizar a projeção). A consulta pré-filtra pelo **menor** prazo da
tabela (`ReassessmentPolicy.menorIntervalo()`, 183d) e a política decide pelo nível de cada cliente:
escrever os quatro prazos em SQL criaria uma segunda cópia da política, e duas cópias divergem.
⚠️ **Limitação conhecida:** o teto é global e a ordem por antiguidade não isola tenants — parceiro
com base maior consome a maior parte da cota diária. É o problema que a cota por tenant do ADR-0015
resolve para ingestão em massa, e a solução deve ser a mesma.

**Canal real de alerta (PagerDuty).** `PagerDutyAlertNotifier` (Events API v2) tira o F5 do papel:
a única implementação de `AlertNotifier` escrevia em log, então fila represada às 3h disparava
alerta para um arquivo. `dedup_key` = código do alerta, então `backlog_analise` repetido atualiza o
incidente aberto em vez de criar dezenas (o dedup por tempo do `AlertEvaluator` evita o tráfego;
este evita o ruído do lado de lá). `WARNING` vai como `warning`, **não** `error` — aviso que acorda
alguém deixa de ser aviso. Chave por env (`PAGERDUTY_ROUTING_KEY`), `@ConditionalOnProperty`
desligado por padrão, timeouts curtos nos **dois** lados: POST pendurado travaria a thread do
scheduler e o monitoramento pararia de monitorar. Ligado sem chave loga erro e segue — o
`LoggingAlertNotifier` já registrou o alerta, nada se perde em silêncio. Alerta nunca carrega
documento nem nome, e é isso que permite mandá-lo a um serviço externo. **Não verificado ao vivo:**
sem routing key neste ambiente, o caminho até o PagerDuty real nunca foi exercitado — validar em
homologação forçando um `backlog_analise` antes de virar plantão.

**Escala horizontal — 5 réplicas em Kubernetes ([plano arquivado](docs/implementation/archive/plano-escala-horizontal.md)).**
O mecanismo difícil já existia e nunca tinha sido exercitado: as quatro filas de trabalho usam
`FOR UPDATE SKIP LOCKED` + lease, a API é stateless e o Flyway pega advisory lock. Faltava tudo em
volta. Agora existem `Dockerfile` multi-stage, CI (`.github/workflows/ci.yml`), manifests em
`deploy/k8s/` e verificação em `kind`.

`SingletonJobLock` (`commons`, V045 na risk-engine e V006 na webhook-api) faz job `@Scheduled` rodar
uma vez **no cluster**: sem ele, `WatchlistImporter`, `PeriodicReassessmentJob`, `AlertEvaluator` e
`DeliveryReconciliationJob` rodavam nos 5 pods — 5 downloads de OFAC/CGU/ONU, e o `max-per-run=200`
do re-KYC virando 1000 avaliações pagas por noite. É **lease em tabela, não advisory lock**: o
advisory é ligado à sessão (exigiria fixar a conexão do Hikari) e a variante `_xact_` manteria
transação aberta durante os minutos de um download. Tem **duas durações**: `lockAtMostFor` (teto,
libera se o pod morrer) e `lockAtLeastFor` (**piso** — sem ele, réplica com cron atrasado por clock
skew reexecuta a janela e o teto do job fica escrito no código e violado na prática). Piso zero é
deliberado no `AlertEvaluator`: ali reexecutar cedo é barato e pular um ciclo é o dano. A tabela é
duplicada por schema de propósito (cada deployable é dono do seu; o escopo do lock é por serviço);
o código é único. ⚠️ Mover para o `commons` **não** publica o bean nos dois serviços: a
`RiskEngineApplication` está em `com.barrier` e escaneia tudo, a `WebhookApplication` está em
`com.barrier.webhook` e escaneia só ela — por isso `webhook.config.JobLockConfig` declara o bean
explicitamente, em vez de ampliar o scan e puxar os beans de outbox junto.

**Estado que é do cluster não pode viver na memória de uma instância** — o padrão apareceu **três
vezes** nesta frente, sempre com comentário explicando por que estava certo, e as três só foram
detectadas rodando com réplicas de verdade:
- `KafkaTopicsConfig` expunha `List<NewTopic>`, que o `KafkaAdmin` **ignora em silêncio** (ele
  procura `NewTopic` ou `KafkaAdmin.NewTopics`). Nenhum tópico era criado, o broker auto-criava com
  **1 partição**, e 1 partição = 1 consumidor: a webhook-api não escalava, por mais pods que
  subissem. Hoje são 3 tópicos × 6 partições, com `KafkaTopicCreationIntegrationTest` provando
  contra o broker — o unitário passava verde sem provar o wiring.
- `WatchlistImportStatus` era `ConcurrentHashMap` por pod, com racional escrito de que isso era
  deliberado. Não era: `replaceSource` grava em `watchlist_entries`, **compartilhada**. A lista
  sempre foi global, só a medição era local — e uma réplica cujo download falhou forçava REVIEW em
  tudo que atendia, com a tabela populada pelas outras. Foi para o banco (V046) junto com o lock,
  na mesma entrega: fazer o lock sozinho deixaria 4 pods sem cobertura mandando 100% para revisão.
- O dedup do `AlertEvaluator` era `HashMap` de instância. Com piso zero a **liderança rotaciona**,
  então o pod A notificava e o B renotificava o mesmo código no ciclo seguinte. Corrigido reusando
  o próprio lease com chave `alert:<código>` — "não repetir antes de X" **é** um lease com piso X.

Autoscaling (`deploy/k8s/autoscaling.yaml`, exige KEDA+Prometheus, não aplicado por padrão): **HPA
por CPU está errado aqui** — o pipeline é I/O-bound em bureau, a CPU fica baixa exatamente quando a
fila afoga (foi assim que 69.809 avaliações ficaram presas sem sinal técnico ruim). Escala por
profundidade **e idade** da fila; a webhook-api por lag, com teto = nº de partições. `minReplicas`
não é 0: escalar a zero mataria os `@Scheduled`, e watchlist que não atualizou às 03:00 é controle
regulatório que não rodou.

⚠️ **Não verificado:** `deploy/verify-disjuncao.sh` (disjunção entre processos sob carga) foi
escrito e não executado ponta a ponta; matar pod no meio de um lote; e o KEDA nunca foi instalado.

**Paralelismo do pipeline (branch `perf/paralelismo-pipeline`) — o que a auditoria de performance
fechou depois.** Três achados, todos do mesmo tipo: controle que *parece* existir e não é exercido.

- **Thread de scheduler é recurso do serviço, não do job.** A risk-engine já tinha subido
  `spring.task.scheduling.pool.size` para 4 (1 thread compartilhada congelava avaliação durante a
  importação das 03:00); a **webhook-api ficou com o default de 1**, e isso só passou a doer quando
  a entrega virou paralela: `retryDue()` espera o lote inteiro (`allOf`) *na thread do scheduler*.
  Lote de 100 com 3 workers são ~34 rodadas; destinos pendurados até o read-timeout de 10s dão
  ~7 minutos com a única thread ocupada — e quem divide essa thread é o `DeliveryReconciliationJob`,
  que o ADR-0017 nomeia dono da recuperação da DLT. O controle de recuperação perdia execuções sem
  erro nenhum. Regra: **paralelizar o trabalho de um job aumenta o tempo que ele ocupa a thread do
  scheduler, não diminui** — quem paraleliza um `@Scheduled` tem de olhar o pool de scheduling junto.

- **Invariante em comentário é invariante que não existe.** A amarra
  `workers <= maximum-pool-size - 2` estava escrita nos dois `application.yml` e verificada em lugar
  nenhum: `ASSESSMENT_WORKERS=12` com `DB_POOL_SIZE=8` subia normal e degradava em *timeout ao obter
  conexão* — sintoma que manda o investigador olhar o banco, não a variável de ambiente. Virou
  `WorkerPoolReadinessGuard` (`commons`, no padrão dos outros cinco guards de startup), declarado
  por bean em cada serviço para o erro citar a propriedade exata e as duas saídas (baixar workers ou
  subir o pool). A **reserva não é folga arbitrária**: são as conexões de quem não passa pelo
  semáforo — ingestão HTTP, relay de outbox, os `@Scheduled`.

- **A ordem por chave de partição tinha uma janela entre pods, e o teste que existia não a via.**
  `DeliveryOrderingIntegrationTest` prova a ordem *dentro de um pod* e passava verde com o furo
  aberto. Descoberto ao investigar por que o teste novo passava sem a correção: `selectClaimable`
  aplica `FOR UPDATE` a **todas** as linhas que retorna, **inclusive as que o filtro em memória
  descarta** — então duas entregas já existentes do mesmo subject ficam as duas travadas, e o caso
  comum nunca esteve furado. A proteção é real e é *efeito colateral* de um filtro que roda depois:
  se o filtro virar SQL, ela some. O que estava furado é a entrega que **nasce depois** do `SELECT`
  da outra réplica (o listener do Kafka insere o tempo todo): ninguém a travou, e sob
  `READ COMMITTED` o `claimed_at` não commitado da irmã é invisível — a outra réplica conclui
  "ninguém em voo" e as duas saem em paralelo. Fechado com `pg_try_advisory_xact_lock` serializando
  **a reivindicação** (um `SELECT` + `UPDATE`s, milissegundos, sem rede); a **entrega** continua
  paralela, que é onde o tempo está. `try` e não a variante que espera: quem não pega a trava pula o
  ciclo (1s) em vez de empilhar transações abertas. Advisory aqui e tabela no `SingletonJobLock` não
  se contradizem — lá o lease dura o download e tem de sobreviver à morte do pod; aqui "morreu o
  pod, soltou o lock" é o comportamento desejado. `claimDue` **recusa rodar fora de transação**,
  porque nela o advisory auto-commita e a exclusão sumiria em silêncio.

⚠️ **Aberto nesta frente:** o `allOf().join()` compra head-of-line blocking (o ciclo dura o elemento
mais lento — 3 destinos pendurados param 97 entregas prontas); o teto de workers é **por pod**,
então o "teto de consultas pagas de bureau" do Javadoc é 5× maior no cluster; e a vazão foi medida
só com **bureau simulado** — com bureau real o gargalo é o pool de conexões, não as threads.
Remedir antes de considerar o ADR-0015 fechado.

**Contrato público da API (posicionamento A, Fase 1).** springdoc 3.0.0 nos dois serviços — a
linha 2.x é para Boot 3 e falha em runtime; a versão é fixada no `pom.xml` raiz porque o BOM do
Boot 4 não a gerencia (mesma situação do `testcontainers-bom`). Na risk-engine há **dois grupos**, e
a separação é de segurança e não de organização: `parceiro` (publicável, 18 rotas) e `admin`
(`/v1/risk-rules`, `/v1/tenants/**`, `/v1/webhook-endpoints/**`), que **nunca** é publicado —
esconder não protege (quem protege é o `AdminApiKeyFilter`), mas não há razão de dar o mapa da
superfície administrativa de graça, mesmo raciocínio que tirou o `/actuator` da porta de negócio. A
webhook-api não tem grupos porque **toda** rota dela é administrativa. UI desligada em `prod` nos
dois (o artefato de produto é o *arquivo* do spec, não uma UI viva no host da API); o spec é gravado
**por teste** e não por plugin de build, para o arquivo publicado ser exatamente o que a aplicação
serve — duas ferramentas divergem, e a divergência aqui é o parceiro integrando contra um contrato
que não existe. `OpenApiCoverageIntegrationTest` é irmão do `ApiRouteCoverageTest`: enumera os
controllers pelo **bytecode** (não por lista escrita à mão), tem guard antivácuo e **quebra o
build** quando rota de negócio nasce sem contrato — provado por mutação, não passa por acidente.
⚠️ Dois defeitos que só apareceram **lendo o spec gerado**, ambos com teste: `AuthenticatedTenant`
(injetado pelo `TenantArgumentResolver` a partir da credencial) era publicado como query parameter
**obrigatório** chamado `tenant`, junto do formato interno de `Tenant` — contrato que descreve
parâmetro inexistente é pior que contrato nenhum, porque o dev externo tenta, falha, e o único
caminho de volta é falar com o time; corrigido com `SpringDocUtils.addRequestWrapperToIgnore`. E não
havia esquema de autenticação declarado (`bearerAuth`). O teste de vazamento administrativo pegou a
regressão de citar `POST /v1/tenants/{id}/api-keys` na descrição do esquema.

**Assinatura de webhook carimbada no tempo (`t=<epoch>,v1=<hex>` sobre `<t>.<corpo cru>`).** Antes
a assinatura cobria só o corpo, e um callback de KYC capturado era **replayável para sempre**; o
`X-Barrier-Event-Id` permite dedup, mas delega ao cliente fazer certo. O instante vai **dentro** do
que se assina: em header próprio, o atacante trocaria o carimbo por "agora" e o replay voltaria a
passar — controle que parece existir e não verifica, o modo de falha recorrente deste projeto. O
ponto entre instante e corpo evita a colisão `t=17`+`"00.x"` vs `t=1700`+`".x"` (tem teste). O
instante é o da **tentativa**, não o da criação da entrega: congelá-lo faria toda retentativa
posterior à tolerância chegar velha e ser recusada, e a máquina de retry queimaria as tentativas
entregando o que o receptor foi instruído a rejeitar. Durante a rotação as duas assinaturas
declaram o **mesmo `t`**, senão o receptor que ainda tem o segredo antigo calcularia sobre outro
`t`. O overload `sign(body, secret)` foi **removido**, não mantido por conveniência — o tipo é a
defesa, mesmo padrão do `SubjectProfileService` que não aceita só o `subjectId`. `v1=` deixa caminho
para `v2=` ao lado, como o `X-Barrier-Signature-Previous` fez para rotação de segredo.
`tools/webhook-receiver.py` (o exemplo que o parceiro copia) aplica tolerância de 5 min — sem a
janela o carimbo é enfeite. Feito antes de haver parceiro integrado, porque depois é quebra de
contrato.

**Replay de decisão (`POST /v1/assessments/{id}/replay`, módulo `replay`).** A trilha era o ativo mais
forte do projeto e estava **gravada e ilegível**: `evaluated_json` com regra suprimida e parâmetro
efetivo, `identity_check_id`/`screening_result_id` exatos (V028), `sources_json` com a versão de cada
lista, `config_history` (V033) — tudo escrito, nada lido. **Nenhuma migration nova:** o item inteiro
existia como dado e faltava como capacidade.

⚠️ **O plano prometia "reproduz o desfecho histórico bit a bit", e isso não é alcançável** — dizer
que era seria o modo de falha recorrente do projeto (controle que parece existir e não verifica).
Dois motivos: **regra é código, não dado** (a decisão de `1.4.0` veio de código que não está mais no
binário, e regra versionada carregável em runtime é recusa já registrada duas vezes); e **o
`RiskContext` não é totalmente reconstruível** — `CompanyProfile` é transiente e nunca persistido,
`SubjectProfile` é mutável e sem histórico. Replayar com o cadastro de hoje produziria diferença
pelo motivo errado, apresentada como mudança de motor.

O que se entrega no lugar são duas afirmações verdadeiras. **`AS_DECIDED`** não reexecuta nada: monta
o dossiê do gravado e **reconfere a aritmética**, recalculando soma/banda/recomendação a partir dos
resultados persistidos e comparando com `risk_scores` — pega adulteração e coluna corrompida, e não
depende de reconstruir insumo nenhum (por isso `TRAIL_INCONSISTENT` **precede** qualquer outro
veredito). **`CURRENT_ENGINE`** roda as regras de hoje sobre a **evidência gravada** e faz o diff
regra a regra.

**A lacuna é apurada, não presumida** — é o que mantém `SAME_DECISION` alcançável em vez de todo
replay sair "degradado": `subject_profiles` não tem histórico mas tem `updated_at`, então cadastro
intocado desde a decisão **não** é lacuna; `company` só é lacuna em PJ (em CPF era nulo na decisão
também); assurance só quando existe alguma verificação (`biometricAttempts` é `COUNT` sobre janela
que termina *agora*). ⚠️ `SubjectProfileService.findDeclared` nasceu aqui: `find` devolve
`SubjectProfile.blank`, que tem `updatedAt = agora`, e sem separar ausência de alteração todo subject
sem cadastro sairia com "cadastro mudou depois da decisão" — lacuna inventada.

**`RiskRule.requires()` não tem default, e é essa a defesa.** Cada regra declara quais campos do
`RiskContext` lê (`ContextInput`); regra cujo insumo não foi reconstruído vira `NOT_REPLAYABLE` e
**não publica** o resultado da execução degradada — porque rodar sobre insumo ausente devolve "não
disparou", indistinguível de "rodou e o cliente estava limpo", a mesma ambiguidade que a V028 gastou
uma migration para eliminar. O compilador obriga a declarar; `RiskRuleContextDeclarationTest` obriga
a declarar **certo**, comparando por bytecode as chamadas a acessores de `RiskContext` com as
constantes referenciadas dentro de `requires()` — sem lista escrita à mão, **provado por mutação**.

**Duas extrações que a entrega forçou, e que valem por si.** `ScoreAggregation` tira soma/banda/
recomendação de dentro do `RiskScoringService`: uma reconferência com cópia própria da regra de
agregação não conferiria nada — as duas divergiriam e a divergência apareceria como "a trilha está
íntegra" (mesmo raciocínio do `ReassessmentPolicy.menorIntervalo()`). E `score()` virou
`evaluate()` + `save()`: o replay chama só `evaluate`, e daí sai de graça que ele não grava
`risk_score`, não dispara `AssessmentCompletedListener`, não toca `subject_risk_state` e não escreve
na outbox — **não ter como**, em vez de lembrar de não chamar. Que ele não gasta consulta paga de
bureau é garantido por ArchUnit (`replay_nao_alcanca_integracao_externa`): o módulo não depende de
nenhum pacote `client`, então não tem como chamar o que não enxerga.

Escopado por tenant como o resto de `/v1/assessments` (404, nunca 403 — 403 confirmaria o id); grupo
`parceiro` do OpenAPI, porque o fiscal audita a instituição contratante e é ela que precisa
responder. `DecisionNotReplayableException` → 409 para avaliação sem decisão do motor. A resposta não
carrega documento nem nome: só código de regra, pontuação, versão de motor e de lista — é o que
permite que um dossiê de auditoria circule.

**Fica aberto, e é o próximo item do backlog:** `config_history` continua sem leitura as-of, então o
dossiê traz o parâmetro efetivo de cada regra (que já vem do `evaluated_json`) mas ainda não a
**autoria** da política vigente. Replay responde *o quê*; política versionada responde *quem*.
E o snapshot que fecharia a lacuna de `company`/`profile` **não** foi feito de propósito: versionar
cadastro multiplica PII sob retenção de 10 anos, e a decisão registrada é resolver isso junto com
criptografia em repouso, não antes.

Próximo: Fase 5 (hardening: OpenAPI, mascaramento) e o backlog de
compliance da Fase 6 (COAF/SISCOAF, retenção de 10 anos, criptografia em repouso, UBO além do
1º grau, bureau real de CPF) — ver [docs/product/backlog.md](docs/product/backlog.md).

Build validado 2026-08-31: `./mvnw test` verde (692 testes na risk-engine + 69 na webhook-api + 32
no commons — **793 no total**, 0 falhas, inclui integração com Testcontainers). **Precisa de Docker rodando** —
sem ele os testes de integração erram com `Can't get Docker image` e a suíte fica verde só na
aparência. Se o Docker Desktop travar em `initializing Inference manager`, o motivo são sockets
órfãos indeletáveis no diretório `Docker/run` do AppData local: renomeie o diretório (apagar não
funciona, nem como admin) e ele recria limpo. `./mvnw spotless:apply` **não roda no JDK 25** — o
google-java-format do spotless 2.44 quebra com `NoSuchMethodError` em `Log$DeferredDiagnosticHandler`;
formatar à mão até subir o plugin.
JDK local: `C:\Users\leona\.jdks\corretto-25.0.3` (setar `JAVA_HOME` antes do `mvnw`).

Peculiaridades do Spring Boot 4 (aprendidas na prática):
- Autoconfig é modularizada: use `spring-boot-starter-kafka` e `spring-boot-starter-flyway`
  (o `spring-kafka`/`flyway-core` crus NÃO ativam a autoconfiguração nem o `@ServiceConnection`).
- Jackson 3 é o padrão (`tools.jackson.*`), com `java.time` embutido; exceções são unchecked.
- `TestRestTemplate` foi removido — usar `RestClient`/`RestTestClient`.
- `@ServiceConnection` de Kafka suporta `org.testcontainers.kafka.KafkaContainer` (imagem
  apache), não o container clássico nem o `ConfluentKafkaContainer`.
- Testcontainers não é gerenciado pelo BOM do Boot 4 — importar `testcontainers-bom`.
