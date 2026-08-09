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
- **Fase 2 (plataforma completa):** viramos **controlador/co-controlador** de dado
  sensível (biometria). Exige KMS, cifragem em repouso, gestão de consentimento e fluxo
  de direitos do titular.

## Requisitos que a arquitetura já endereça

1. **Evidência da decisão persistida** — `risk_scores` guarda score, nível, fatores e a
   versão do motor (`engine_version`); `identity_checks` e `screening_results` guardam o
   insumo. *Não* existe serviço de Audit separado, nem eventos intermediários por etapa:
   há um único evento (`barrier.assessment.completed`).
2. **Rastreabilidade** — `assessmentId` liga as etapas de uma decisão de ponta a ponta.
3. **Cobertura de listas verificável** — `WatchlistImportStatus` registra o resultado da
   última importação por fonte; `WatchlistHealthIndicator` derruba o health quando falta
   cobertura de sanções ou PEP; `ScreeningCoverageRiskRule` impede aprovação automática de
   avaliação decidida sem lista. Antes, importação falha = tabela vazia = todos aprovados,
   com health verde.
4. **PEP** — `PepWatchlistSource` ingere o cadastro da CGU (`MatchType.PEP`), que é o
   insumo da `PepRiskRule`. ⚠️ O formato do CSV **não foi verificado contra o portal real**
   (403 no ambiente de desenvolvimento) — validar antes de confiar em produção.
4. **Evidência de decisão** — cada `risk.scored` e `case.decided` guarda os fatores que
   levaram à decisão (explicabilidade regulatória).
5. **Cadastro mínimo (CMN 4.753)** — `SubjectProfile` cobre o checklist por tipo de
   documento (PF/PJ); avaliação com cadastro incompleto é rebaixada para revisão manual em
   vez de aprovar automaticamente sem os dados exigidos.
6. **Abordagem baseada em risco com parâmetro ajustável** — `tenant_risk_config` permite
   ao parceiro calibrar parâmetros de regras de apetite de risco (nunca das regras
   regulatórias fixas — sanção, PEP, identidade — que ficam travadas por ArchUnit); o
   registry de regras permite desligar uma regra com efeito imediato em incidente
   operacional, sem esperar deploy.

## Lacunas conhecidas (não confundir com "endereçado")

- **CSNU/ONU** (Lei 13.810/19 — indisponibilidade imediata de ativos) não implementado.
- **CEIS/CNEP** são inidoneidade em licitação, não sanção financeira; hoje entram como
  `MatchType.SANCTION` e bloqueiam. Separar em categoria própria.
- **Monitoramento contínuo / rescreening**: o motor roda uma vez, no onboarding. Cliente
  aprovado que entra em lista depois nunca é reavaliado.
- **KYC de pessoa física**: sem bureau real de CPF, `CpfBureauReadinessGuard` impede a
  subida em produção.
- **UBO além do 1º grau**, procuradores e percentual de participação.
- **Reprodutibilidade da decisão**: `tenant_risk_config` e `risk_rule_registry` são
  mutáveis sem histórico, e o snapshot da watchlist usada não é preservado.

## A endereçar explicitamente na fase 2

- Retenção completa de 10 anos com política de expurgo.
- Cifragem em repouso e KMS para dado sensível.
- Fluxo de direitos do titular (acesso, correção, anonimização).
- Comunicação automatizada ao COAF/SISCOAF.
