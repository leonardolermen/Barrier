# ADR-0012: Cadastro (Subject Profile) separado do Subject, com gate de completude em EM_REVISAO

- **Status:** Aceito
- **Data:** 2026-07-13

## Contexto

A Resolução CMN 4.753 exige dados mínimos de cadastro do cliente final (PF: nascimento,
nacionalidade, endereço, ocupação; PJ: fundação, CNAE, endereço, representante legal — entre
outros). O `Subject` (ver [ADR-0011](0011-subject-compartilhado-acesso-por-associacao.md)) só
guardava documento, tipo e nome — o mínimo para dedup/identidade, deliberadamente enxuto porque
a Fase 1 do produto assume o papel de **operador** LGPD (ver `docs/architecture/compliance.md`),
não de controlador.

Para PJ, o bureau (`BrasilApiBureauProvider`) já retorna fundação/CNAE/QSA no momento da
verificação de identidade (`CompanyProfile`), mas esse dado era descartado depois de alimentar
as risk rules — não persistia em lugar nenhum, então não dava para auditar sem reconsultar o
bureau externo.

## Decisão

Criar um agregado novo, `SubjectProfile` (1:1 com `subjects` via `subject_id UNIQUE`), em vez de
inchar o `Subject`:

- `Subject` continua sendo a identidade mínima global (dedup por documento).
- `SubjectProfile` guarda o cadastro completo, com campos nullable divididos por tipo de
  documento (mesma tabela, `partners` de PJ serializado em `partners_json` — mesmo padrão de
  `hits_json`/`results_json` já usado no projeto).
- O cadastro é **progressivo**: `PUT /v1/subjects/{document}/profile` aceita atualização
  parcial a qualquer momento, sem exigir todos os campos de uma vez.
- Um novo `RegistrationCompleteness.evaluate(documentType, profile)` é o checklist mínimo por
  tipo — não é validação de borda (a API aceita parcial), é o **gate antes da aprovação
  automática**: `AssessmentProcessor` passa a consultar a completude depois do score de risco e,
  se o cadastro estiver incompleto e a recomendação fosse `APROVADO`, rebaixa para
  `EM_REVISAO` com um fator explicando os campos faltantes. Reaproveita o workflow humano que já
  existe (`POST /v1/assessments/{id}/decision`) em vez de criar um status novo.
- Os dados objetivos de PJ vindos do bureau (`CompanyProfile`) passam a ser persistidos no
  `SubjectProfile` assim que a verificação de identidade retorna, em vez de descartados.

## Alternativas consideradas

- **Adicionar as colunas direto em `subjects`** — mistura identidade mínima (dedup) com cadastro
  mutável, e força todo consumidor de `Subject` a lidar com um monte de campos nullable que só
  fazem sentido em contextos específicos (compliance/revisão). Rejeitado.
- **Duas tabelas separadas por tipo (`subject_profiles_pf` / `subject_profiles_pj`)** —
  mais normalizado, mas duplica a camada inteira (entity/repo/service) para um ganho pequeno,
  já que o volume de campos exclusivos por tipo é baixo. Rejeitado por complexidade
  desproporcional ao ganho.
- **Novo status de assessment (`CADASTRO_INCOMPLETO`)** em vez de reaproveitar `EM_REVISAO` —
  mais explícito, mas exigiria replicar toda a lógica de decisão humana que `EM_REVISAO` já tem
  (endpoint, evento, webhook). Rejeitado por ora; pode ser revisitado se o volume de casos
  justificar um status dedicado.

## Consequências

- **Positivas:** completude cadastral auditável e rastreável; dado do bureau para PJ deixa de
  ser descartado; nenhuma mudança de schema em `subjects`/`assessments` além de uma coluna nova
  em tabela nova.
- **Negativas / custos:** mais uma consulta (completude) no processamento de cada avaliação;
  cadastro incompleto nunca aprova automaticamente, então times que hoje dependem de aprovação
  100% automática vão ver mais avaliações caindo em `EM_REVISAO` até o cadastro ser preenchido.
- **Riscos e mitigações:** guardar mais dado pessoal (endereço, renda, ocupação) empurra o
  projeto para mais perto do papel de **controlador** LGPD do que o "operador" assumido hoje em
  `docs/architecture/compliance.md` — precisa ser revisitado junto com a Fase 2 (retenção,
  criptografia em repouso, direitos do titular).
