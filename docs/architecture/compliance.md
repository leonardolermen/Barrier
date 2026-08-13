# Requisitos regulatórios

O domínio é regulado. Estes requisitos dirigem decisões arquiteturais e não são opcionais.

## Normas aplicáveis

| Norma                          | O que exige (resumo)                                            | Impacto arquitetural                          |
|--------------------------------|-----------------------------------------------------------------|-----------------------------------------------|
| **Resolução BCB nº 44/2020**   | Política de PLD-FT, CDD/EDD, abordagem baseada em risco          | Serviços de Screening, Risk e Case management  |
| **Circular BCB 3.978/2020**    | Procedimentos de PLD-FT; guarda de registros por **10 anos**    | Auditoria imutável + retenção longa            |
| **Lei 9.613/98**               | Lavagem de dinheiro; comunicação ao COAF                        | Reporting COAF/SISCOAF (fase 2)                |
| **LGPD (Lei 13.709/18)**       | Base legal, dado sensível (biometria), retenção, direitos titular | Segregação de dado sensível, papéis controlador/operador |
| **Resolução CMN 4.753**        | Cadastro de clientes                                            | `SubjectProfile` (cadastro progressivo, gate de completude) — [ADR-0012](../adr/0012-subject-registration-profile.md), **implementado** |

## Papéis LGPD por fase

- **Fase 1 (motor de risco):** somos **operador**; o cliente é **controlador**. Guardamos
  o mínimo necessário para a trilha de auditoria (input, decisão, evidência).
- **Fase 2 (plataforma completa, [ADR-0016](../adr/0016-plataforma-completa-modelo-b.md)):**
  viramos **controlador de dado pessoal** (cadastro, resultados de verificação) — com base legal,
  consentimento por finalidade, direitos do titular e retenção de 10 anos como obrigação própria.
  **Não** de dado sensível: documentoscopia e biometria guardam o resultado da verificação, nunca
  a imagem nem o template biométrico. Biometria é sensível pelo art. 5º, II; resultado de
  comparação não é. Isso derruba a exigência de tratamento de dado sensível e elimina o pior
  cenário de vazamento — base biométrica vazada não se revoga.
  Continua exigindo KMS e cifragem em repouso (dado pessoal comum também vaza), gestão de
  consentimento e fluxo de direitos do titular.
  O custo da escolha: sem a imagem, a evidência de uma verificação depende da retenção contratada
  com o provedor — mitigado por contrato, pelo hash do que foi submetido e pelo registro da versão
  do algoritmo.

## Requisitos que a arquitetura já endereça

1. **Evidência da decisão persistida** — `risk_scores` guarda score, nível, fatores e a
   versão do motor (`engine_version`); `identity_checks` e `screening_results` guardam o
   insumo. *Não* existe serviço de Audit separado, nem eventos intermediários por etapa:
   há um único evento (`barrier.assessment.completed`).
2. **Rastreabilidade** — `assessmentId` liga as etapas de uma decisão de ponta a ponta.
3. **Cobertura de listas verificável** — `WatchlistImportStatus` registra o resultado da
   última importação por fonte; `WatchlistHealthIndicator` derruba o health quando falta
   cobertura de sanções ou PEP; `ScreeningCoverageRiskRule` impede aprovação automática de
   avaliação decidida sem lista (SANCTION e PEP, incondicional). Desde esta branch, também exige
   ADVERSE_MEDIA — mas só quando existe `NegativeMediaProvider` autoritativo (contratado)
   configurado: sem provedor real (hoje o único é o stub de dev), mídia negativa não entra na
   exigência, porque a ausência vale para 100% da base e pontuar por avaliação só encheria a fila
   de revisão sem informar nada. Antes, importação falha = tabela vazia = todos aprovados, com
   health verde.
4. **PEP** — `PepWatchlistSource` ingere o cadastro da CGU (`MatchType.PEP`), que é o
   insumo da `PepRiskRule`. ⚠️ O formato do CSV **não foi verificado contra o portal real**
   (403 no ambiente de desenvolvimento) — validar antes de confiar em produção.
5. **Evidência de decisão** — cada `risk.scored` e `case.decided` guarda os fatores que
   levaram à decisão (explicabilidade regulatória).
6. **Cadastro mínimo (CMN 4.753)** — `SubjectProfile` cobre o checklist por tipo de
   documento (PF/PJ); avaliação com cadastro incompleto é rebaixada para revisão manual em
   vez de aprovar automaticamente sem os dados exigidos.
7. **Abordagem baseada em risco com parâmetro ajustável** — `tenant_risk_config` permite
   ao parceiro calibrar parâmetros de regras de apetite de risco (nunca das regras
   regulatórias fixas — sanção, PEP, identidade — que ficam travadas por ArchUnit); o
   registry de regras permite desligar uma regra com efeito imediato em incidente
   operacional, sem esperar deploy.
8. **CSNU/ONU** (Lei 13.810/19 — indisponibilidade imediata de ativos) — `UnWatchlistSource`
   ingere a lista consolidada da ONU (XML, `INDIVIDUALS`+`ENTITIES`, cada alias vira entrada
   própria) e declara `provides() = SANCTION`; está **ligada por padrão no profile `prod`**
   (não é decisão de apetite, ao contrário de CGU/OFAC). Sem documento — casamento sempre por
   nome, escalando para revisão humana como o resto de `SanctionRiskRule`, nunca reprovando
   sozinha.
9. **CEIS/CNEP separados de sanção financeira** — `MatchType.DEBARMENT` é categoria própria;
   `CeisWatchlistSource`/`CnepWatchlistSource` produzem `DEBARMENT`, não `SANCTION`, e
   `DebarmentRiskRule` nunca reprova sozinha (peso de alerta: REVIEW para match por documento
   no titular, apenas soma ao score para match por nome; apontamento de sócio nunca escala).
   Inidoneidade em licitação deixou de gerar `REJECT` automático. **Não** é regra regulatória
   de propósito — nenhuma norma do Bacen manda recusar conta por inidoneidade — e por isso é
   desligável pelo registry, diferente de `SANCTION`/`PEP`/`IDENTITY`.
10. **Monitoramento contínuo** — `RescreeningService` reavalia quando o delta de uma
    importação de watchlist passa a apontar um cliente já aprovado (por documento e, para
    OFAC/CSNU que não publicam documento, por nome); reavaliar é submeter uma avaliação nova
    pelo pipeline normal (`origin = RESCREENING`), não um caminho paralelo de decisão. ⚠️
    Cobre só quem **entra** na lista — quem sai não dispara nada — e não faz revisão
    periódica por prazo (ver lacuna abaixo).
11. **Reprodutibilidade da decisão** — `tenant_risk_config_history` e
    `risk_rule_registry_history` (V033) guardam a linha do tempo de configuração (quem mudou
    o quê e quando, inclusive kill switch); `screening_results.sources_json` preserva o
    snapshot de fonte→versão da watchlist consultada; `evaluated_json` guarda toda regra que
    rodou com o desfecho (`TRIGGERED`/`NOT_TRIGGERED`/`SUPPRESSED`) e o parâmetro efetivo
    usado, inclusive das regras que passaram. ⚠️ Fica aberto o histórico de
    `subject_profiles` (dado pessoal — decidir junto com retenção/cifragem da Fase 6).

## Lacunas conhecidas (não confundir com "endereçado")

- **KYC de pessoa física**: sem bureau real de CPF, `CpfBureauReadinessGuard` impede a
  subida em produção.
- **UBO além do 1º grau**, procuradores e percentual de participação — QSA não é
  beneficiário final: sem percentual de participação, o corte de 25% da Resolução BCB 44 é
  inalcançável com o dado disponível; sócios não têm documento (screening só por nome, alto
  falso positivo) e não há ligação PF↔PJ (sócio de uma PJ não vira/consulta um subject PF).
- **Cobertura de QSA depende do bureau contratado** (antes desta branch, fail-open
  silencioso): o `basic_data` da BigBoost não traz QSA — quando ela atende, o
  `CompanyProfile` chegava com sócios vazios e nada registrava que o KYB não rodou.
  `CorporateStructureCoverageRiskRule` (V039, regulatória) fecha o silêncio forçando REVIEW
  quando isso acontece, mas não resolve a causa: continua sem existir provedor de QSA
  completo em produção fora da BrasilAPI (API pública, sem SLA, já sinalizada pelo
  `CnpjBureauReadinessGuard`). E a regra não distingue "bureau sem QSA" de "empresa sem
  sócio" (MEI/empresário individual) — decisão fail-closed registrada no Javadoc da regra.
- **Mídia negativa sem provedor em produção**: o único provedor é o
  `StubNegativeMediaProvider`, que casa contra um CSV vazio por padrão — não há
  BigBoost/LexisNexis/Dow Jones contratado. `ScreeningCoverageRiskRule` força REVIEW quando
  falta essa cobertura (não aprova em silêncio); `WatchlistReadinessGuard` só **avisa** em
  prod, não impede a subida — não existe hoje provedor contratável para exigir.
- **Representante legal de PJ não verificado**: `legalRepresentative` é campo declarado, sem
  documentoscopia/biometria como há para PF, nem conferência contra o QSA quando ele existe.
  Qualquer um pode se declarar representante de qualquer CNPJ.
- **Revisão periódica por banda de risco**: a Circular 3.978 exige revisão proporcional ao
  risco (cliente HIGH revisto mais que LOW); hoje só existe o rescreening por delta de lista
  (item 10 acima) — um cliente CRITICAL que nunca casa com lista nenhuma nunca é reavaliado
  por prazo.

## A endereçar explicitamente na fase 2

- Retenção completa de 10 anos com política de expurgo.
- Cifragem em repouso e KMS para dado sensível.
- Fluxo de direitos do titular (acesso, correção, anonimização).
- Comunicação automatizada ao COAF/SISCOAF.
