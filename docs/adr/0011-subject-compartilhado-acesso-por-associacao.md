# ADR-0011: Subject compartilhado (1 por documento) com acesso por associação

- **Status:** Aceito
- **Data:** 2026-07-07

## Contexto

Um CPF/CNPJ é uma entidade do mundo real que várias empresas (tenants) podem avaliar. Duas
necessidades: (1) **isolamento** — a empresa 2 não pode descobrir/consultar um cliente que só
a empresa 1 tem (LGPD: a existência de alguém na base não pode ser "pescável"); (2) **dedup** —
não faz sentido guardar N cópias do mesmo documento.

O isolamento de **avaliação** já existe (`assessments.tenant_id`, ver
[tenancy](../../CLAUDE.md)). Falta o isolamento no nível do **cliente final** (o documento).

## Decisão

Modelar o cliente final como um **subject global** (1 por documento) e controlar o acesso por
uma **associação** tenant↔subject:

- `subjects`: 1 linha por `(document_type, document)` (UNIQUE). É a entidade compartilhada.
- `tenant_subjects`: aresta de **visibilidade**. Uma empresa só "enxerga" um subject se existe
  esse vínculo.
- `assessments` referencia `subject_id` (o subject) além de `tenant_id` (quem pediu).

Fluxo no `POST /v1/assessments`: resolve o tenant → **acha-ou-cria** o subject por documento →
**garante o vínculo** `tenant_subjects` → cria o assessment. O `GET /v1/subjects/{documento}`
só retorna se existe vínculo; caso contrário **404** (não vaza existência).

### O que é compartilhado vs privado

- **Compartilhado:** identidade do subject (o mesmo documento é o mesmo subject).
- **Privado do tenant:** os `assessments` e suas decisões de risco.
- **Nunca exposto:** a lista de tenants ligados a um subject (vazaria que outra empresa também
  tem esse cliente). A dedup é interna.

### Conservador por ora

O **cache compartilhado de dados objetivos** (reaproveitar entre tenants a consulta de Receita/
watchlist de um subject) fica para um passo 2 **opt-in**. Agora o subject só unifica identidade
e histórico; cada tenant dispara e guarda o seu.

## Alternativas consideradas

- **Só `assessments.tenant_id` (sem subject)** — isola avaliações, mas duplica o documento por
  tenant e não permite histórico por cliente nem dedup. Insuficiente para o requisito.
- **Compartilhar tudo (inclusive risco) entre tenants** — eficiente, mas cruza dado sensível
  entre empresas no dia 1. Adiado (opt-in).

## Consequências

- **Positivas:** isolamento por associação (anti-fishing); dedup por documento; base para
  histórico/perfil do cliente (monitoramento contínuo, fase 2).
- **Negativas / custos:** `POST` faz mais escritas (subject + vínculo); concorrência no
  acha-ou-cria exige tratar violação de UNIQUE.
- **Decisão humana (EM_REVISAO):** o aceitar/recusar é **por tenant, no assessment** — nunca no
  subject. Empresa 1 pode aceitar e empresa 2 recusar o mesmo documento (ADR futuro do endpoint
  de decisão).
