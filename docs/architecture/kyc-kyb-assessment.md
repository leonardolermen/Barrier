# Avaliação do fluxo de KYC e KYB

- **Data:** 2026-08-13
- **Escopo:** o fluxo de ponta a ponta, não o código. Complementa
  [compliance.md](compliance.md) (obrigações) e
  [plano-remediacao-auditoria.md](../implementation/plano-remediacao-auditoria.md) (o que falta
  para produção).

**Por que existe:** o KYC evoluiu muito mais que o KYB, e a diferença não estava registrada em
lugar nenhum. Quem lê o CLAUDE.md hoje encontra "KYB de 1º grau já ativo" e conclui que o básico
está resolvido. Este documento diz o que cada lado faz de fato.

---

## O fluxo hoje

**KYC (PF)** — intake → bureau (situação cadastral decide antes da comparação de nome) →
screening (OFAC, CSNU, PEP/CGU, CEIS/CNEP, mídia negativa) → 12 regras de risco → gate de
cadastro (CMN 4.753) → verificação de campo (OTP, bureau, documentoscopia, RFB) → assurance
(documentoscopia, depois biometria) → decisão → EDD humano → webhook → rescreening contínuo por
delta de lista.

**KYB (PJ)** — intake → bureau CNPJ → `CompanyProfile` (abertura, CNAE, QSA) → 3 regras de PJ →
screening dos sócios e do representante legal → mesmo gate e mesma decisão.

---

## 1. 🔴 O QSA some em produção, em silêncio

`application-prod.yml` liga a BigBoost. O `basic_data` de empresas da BigBoost **não traz QSA**
(registrado no CLAUDE.md). A BrasilAPI traz, mas é `@Order(10)`, desligável, e o próprio
`CnpjBureauReadinessGuard` avisa que é API pública sem SLA sustentando controle regulatório.

Quando a BigBoost atende, o `CompanyProfile` chega com sócios vazios, e em cadeia:

- `CorporateStructureRiskRule` não dispara — sem sócio, não há sócio estrangeiro ou PJ a detectar;
- o screening de partes relacionadas roda sobre lista vazia — nenhum sócio conferido contra
  OFAC, CSNU ou PEP;
- a avaliação conclui `APROVADO` normalmente.

**Nada registra que o KYB não foi feito.** Sem fator na trilha, sem rebaixamento, sem aviso. Uma
PJ com sócio sancionado é aprovada automaticamente e a trilha afirma que passou pelo screening.

É o mesmo modo de falha que o projeto já fechou para watchlist — *importação falha → tabela
vazia → CLEAR → todos aprovados, com health verde* — reaparecendo um módulo ao lado. O
`ScreeningCoverageRiskRule` cobre cobertura de **lista**, não de **estrutura societária**.

## 2. 🔴 Mídia negativa tem cobertura zero em produção, também em silêncio

Verificado no código: `ScreeningCoverageRiskRule:31` e `WatchlistReadinessGuard:42` declaram
`REQUIRED = Set.of(MatchType.SANCTION, MatchType.PEP)`. `ADVERSE_MEDIA` e `DEBARMENT` ficam de
fora.

O único provedor de mídia negativa é o `StubNegativeMediaProvider`, que casa contra um CSV
**vazio por padrão**. Em produção, sem provedor contratado, `NegativeMediaRiskRule` nunca dispara
e nenhum guard reclama.

Mesmo padrão da `PepRiskRule` antes da CGU, que o plano de remediação descreve como *"o controle
rodava, registrava que rodou, e não achava ninguém"* — desta vez sobre uma funcionalidade que o
repositório apresenta como entregue.

**Resolvido nesta branch, na segunda tentativa.** A primeira versão fez
`ScreeningCoverageRiskRule.REQUIRED` incluir `ADVERSE_MEDIA` incondicionalmente, igual a
`SANCTION`/`PEP`, e quebrou a suíte completa de um jeito pior do que o problema que existia para
fechar: `ADVERSE_MEDIA` não é populada em `WatchlistImportStatus.coverage()` — mídia negativa é
`NegativeMediaProvider`, consultado ao vivo por avaliação, não importado como `WatchlistSource` —
então a cobertura estava sempre ausente e a regra pontuava **100% das avaliações**, mandando toda
a base para `EM_REVISAO`. É exatamente o cenário que o plano de remediação registra como causa do
`SOLICITAR_DOCUMENTO` (7501 de 7529 avaliações em revisão por ruído, cegando operações) —
substituir um fail-open por indisponibilidade operacional total não é fechar o gap, é trocar de
gap.

A correção aplica o mesmo conceito que separa bureau real de stub
(`BureauProvider.authoritative()`): `NegativeMediaProvider` ganhou `authoritative()` (default
`true`, `StubNegativeMediaProvider` sobrescreve para `false`), e `ScreeningCoverageRiskRule` só
exige `ADVERSE_MEDIA` quando existe pelo menos um provider autoritativo configurado. Hoje, sem
contrato, a regra não pontua por mídia negativa — a ausência é conhecida e vale para toda a base;
o aviso de startup do `WatchlistReadinessGuard` já cobre isso. Contratado um provedor real, a
exigência entra como sanção e PEP.

## 3. "KYB de 1º grau" superestima o que existe

O QSA é o quadro de sócios e administradores. **Não é beneficiário final:** não traz percentual
de participação, não traz cadeia societária, não traz documento dos sócios.

A Resolução BCB 44 e a Circular 3.978 pedem identificar a pessoa natural que em última instância
possui ou controla. O que existe é *screening de nomes de sócios* — útil, e não é UBO.

Três consequências:

- **Sócios não têm documento.** O screening deles é fuzzy por nome apenas: alto falso positivo, e
  impossível rodar o sócio pelo pipeline de KYC (sem CPF não há bureau nem verificação de
  identidade).
- **Não há ligação PF↔PJ.** "Sócio João" numa avaliação de CNPJ não cria nem consulta um subject
  PF. São mundos separados.
- **Sem percentual, o corte de 25% é inalcançável** com o dado disponível.

Limite de fornecedor, não de implementação — o plano já registra "UBO ≥25% bloqueado por
contrato". O problema é a descrição, que sugere resolução.

## 4. Ninguém prova que o representante legal representa a empresa

Para PF há documentoscopia e biometria provando que a pessoa é o titular. Para PJ,
`legalRepresentative` é **campo declarado**. Qualquer um se declara representante de qualquer
CNPJ.

É o buraco mais visível comercialmente: onboardar uma empresa significa verificar que o humano do
outro lado pode vinculá-la.

O caminho existe meio construído — o representante declarado poderia ser conferido contra o QSA
(quando há QSA) e submetido ao fluxo de assurance como PF. Nada disso é feito.

## 5. Sinais de laranja e empresa de fachada não são explorados

`shareCapital` está no `SubjectProfile`, no DTO, na entidade e no repositório — e **nenhuma regra
de risco o lê**. Campo declarado sem consumidor.

Sinais que o dado já disponível permitiria e que não existem: capital social incompatível com a
atividade declarada, endereço compartilhado com N empresas, sócio que é sócio de N empresas,
empresa sem quadro de funcionários. `NewCompanyRiskRule` e `SensitiveCnaeRiskRule` cobrem duas
dimensões.

## 6. Revisão periódica por nível de risco não existe

O `RescreeningService` reavalia quando uma lista **passa a apontar** o cliente. A Circular 3.978
também exige revisão periódica proporcional ao risco — cliente HIGH revisto mais frequentemente
que LOW.

Hoje um cliente aprovado como CRITICAL que nunca casa com lista nenhuma nunca mais é olhado. É
obrigação, não melhoria.

## 7. O `compliance.md` está desatualizado, e para menos

Ele lista como lacuna quatro coisas **já feitas**: CSNU/ONU, separação de CEIS/CNEP de sanção
financeira, monitoramento contínuo, e reprodutibilidade da decisão (histórico de configuração
V033 e snapshot de watchlist em `sources_json`).

Documento de compliance errado é problema nas duas direções: quem o usar para responder a uma
fiscalização reporta lacunas inexistentes — e, se está errado no sentido pessimista em quatro
itens, não se pode confiar nele no sentido otimista.

---

## Ordem de ataque

| # | Item | Custo | Fecha |
|---|---|---|---|
| 1 | Guard de cobertura de QSA | baixo | fail-open regulatório |
| 2 | `ADVERSE_MEDIA` na exigência de cobertura | baixo | fail-open silencioso |
| 3 | Corrigir `compliance.md` | baixo | documento que um auditor lê primeiro |
| 4 | Verificação do representante legal | médio | o que torna o KYB vendável |
| 5 | Ligação PF↔PJ para sócios | alto | depende de provedor KYB com documento |
| 6 | Revisão periódica por banda de risco | médio | obrigação da Circular 3.978 |

Os itens 1–3 fecham buracos que hoje aprovam cliente sem controle e não deixam rastro disso.
