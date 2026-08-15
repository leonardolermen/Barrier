# Barrier — contexto do projeto

Plataforma de KYC / PLD-FT para atender às normas do Banco Central. Modelo atual: **motor
de risco** (operador LGPD), evoluindo para plataforma completa. Ver [README](README.md).

## Ao implementar código, siga SEMPRE

- **O que falta para produção:** [docs/implementation/plano-remediacao-auditoria.md](docs/implementation/plano-remediacao-auditoria.md)
  — plano vivo da auditoria de KYC/PLD-FT, com critérios de pronto. Consulte antes de propor
  trabalho novo: o que está lá é o que reduz risco de verdade.
- **Padrões de código:** [docs/implementation/coding-standards.md](docs/implementation/coding-standards.md)
- **Plano da Risk Engine:** [docs/implementation/risk-engine-plan.md](docs/implementation/risk-engine-plan.md)
- **Lições do BMP Origem:** [docs/implementation/licoes-do-origem.md](docs/implementation/licoes-do-origem.md)
  — estudo comparativo com a esteira de KYC que roda em produção na BMP (Origem/Mishmar/
  bureaus-manager/tzofe): o que importar, em que ordem, e **o que não copiar**.
- **Fila de execução dessas lições:** [docs/implementation/fila-origem.md](docs/implementation/fila-origem.md)
  — F1–F9 com escopo, arquivos, dependências e critério de pronto. F1 entregue (ADR-0017).
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

Próximo: Fase 5 (hardening: OpenAPI, mascaramento) e o backlog de
compliance da Fase 6 (COAF/SISCOAF, retenção de 10 anos, criptografia em repouso, UBO além do
1º grau, bureau real de CPF) — ver `docs/implementation/risk-engine-plan.md`.

Build validado: `./mvnw test` verde (530 testes na risk-engine + 53 na webhook-api + 27 no
commons — 610 no total, inclui integração com Testcontainers). `./mvnw spotless:apply` **não roda no JDK 25** — o
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
