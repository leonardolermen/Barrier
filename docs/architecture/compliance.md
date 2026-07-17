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

1. **Trilha de auditoria imutável** — serviço de Audit consome todos os eventos; eventos
   são *append-only* e versionados.
2. **Reprocessamento** — como o fluxo é orientado a eventos, decisões podem ser reavaliadas
   por replay sem reescrever a lógica.
3. **Rastreabilidade** — `assessmentId` como *correlation id* liga todas as etapas de uma
   decisão, de ponta a ponta.
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

## A endereçar explicitamente na fase 2

- Retenção completa de 10 anos com política de expurgo.
- Cifragem em repouso e KMS para dado sensível.
- Fluxo de direitos do titular (acesso, correção, anonimização).
- Comunicação automatizada ao COAF/SISCOAF.
