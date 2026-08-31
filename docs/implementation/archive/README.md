# Arquivo — planos encerrados

Estes documentos **não são backlog**. O backlog vivo, e único, é
[docs/product/backlog.md](../../product/backlog.md).

Eles ficam aqui porque metade do valor deles nunca esteve nos checkboxes: está no **racional das
recusas** e nas **hipóteses que foram reprovadas por medição**. Apagar isso faria a mesma pergunta
voltar em três meses sem a resposta junto — que é exatamente o problema que o critério escrito do
schema registry (F9) existe para evitar.

**Regra de leitura:** nada aqui é fonte de verdade sobre o estado atual. Um item marcado `[ ]` num
plano encerrado pode já estar pronto — foi assim que quatro itens ficaram abertos por meses depois
de resolvidos (ver a tabela de reconciliação abaixo). Para saber o que falta, leia o backlog.

---

## O que cada um foi

| Documento | O que era | Encerrado porque |
|---|---|---|
| [plano-remediacao-auditoria.md](plano-remediacao-auditoria.md) | Auditoria de **segurança e integridade da decisão**. Perguntava *"a decisão está correta?"* | 45 de 67 itens fechados; os abertos migraram para o backlog, e 4 estavam abertos por desatualização, não por falta de trabalho |
| [plano-auditoria-2026-08-18.md](plano-auditoria-2026-08-18.md) | Auditoria externa de `e141669`, com notas por dimensão. Perguntava *"isto vira produto em produção?"* | P0 inteiro fechado; P1–P4 migraram para o backlog. **A seção "o que a auditoria disse para não mudar" continua valendo** e foi copiada para o backlog |
| [plano-escala-horizontal.md](plano-escala-horizontal.md) | 5 réplicas em k8s: container, CI, locks singleton, partições de Kafka | 16 de 19 fechados; as 3 verificações que faltam (`kind` sob carga, matar pod no meio do lote, KEDA) migraram |
| [plano-produto-api-first.md](plano-produto-api-first.md) | Decisão de posicionamento + fases de execução | A **decisão** virou [ADR-0020](../../adr/0020-posicionamento-motor-de-decisao-api-first.md); as **fases** viraram a espinha do backlog |
| [fila-origem.md](fila-origem.md) | F1–F9, as lições do BMP Origem convertidas em fila | **Drenada** — todas entregues. É o registro do porquê de `mesa`/`riskstate`/`monitoring`/`behavior` existirem |
| [risk-engine-plan.md](risk-engine-plan.md) | Plano original de implementação, Fases 0–8 | Fases 0–4 concluídas; Fase 5 (hardening) e Fase 6 (compliance) migraram para o backlog |

---

## Reconciliação — o que estava marcado errado

Conferido contra o código em 2026-08-31, não contra o texto. **Estes itens constavam abertos e
estavam prontos**, e é o motivo direto de haver um backlog único agora: quatro planos vivos e
sobrepostos produzem exatamente este erro.

| Item | Onde constava aberto | Estado real |
|---|---|---|
| `JSONB` nas colunas de evidência | remediação | ✅ V026 (evidência) + V047/V007 (payload de outbox e entrega, em TEXT de propósito — o HMAC é sobre os bytes lidos) |
| Reescrever o match por nome (`findAll()`) | remediação | ✅ V048, blocking por trigrama; 360ms → 13ms com 100k entradas |
| Paralelizar o processamento | remediação | ✅ `perf/paralelismo-pipeline` — **feito fora de ordem**, a cota deveria ter vindo antes (ver backlog) |
| Métrica de idade da fila | remediação | ✅ `BacklogAgeAlertRule` + `snapshot.oldestPending()` |
| Contract tests + golden dataset | remediação | 🟡 golden dataset ✅ (48 pares, curva de recall) · contract tests ⬜ |
| Case management e COAF | remediação | 🟡 mesa/F7 ✅ · COAF ⬜ |
| Fila de EDD separada e 4-eyes | remediação | 🟡 `SOLICITAR_DOCUMENTO` + fila da mesa ✅ · 4-eyes ⬜ |
| Limite de vazão na entrega de webhook | remediação | 🟡 semáforo de workers ✅ (é **por pod**) · teto por tenant/destino ⬜ |
| `ENGINE_VERSION: 1.7.0` | remediação, seção de convenções | ❌ era `1.8.0` |

---

## O que estes documentos protegem, e não deve ser redecidido sem ler

Recusas com critério registradas aqui. Reabrir qualquer uma exige argumentar contra o racional,
não ignorá-lo:

- **Regra customizável pelo parceiro** — dá ao regulado o botão de afrouxar o próprio controle de
  PLD-FT. A forma regulatória de "flexibilidade por parceiro" é política versionada com vigência,
  autoria e replay, não editor de regra
  ([plano-auditoria](plano-auditoria-2026-08-18.md#por-que-não-regras-customizáveis-pelo-parceiro)).
- **Regra-como-dado editável em runtime** — sacrificaria `ENGINE_VERSION` e a trilha reproduzível
  ([fila-origem, F8](fila-origem.md)).
- **Schema registry** — entra quando o produtor deixar de ser único, ou na primeira quebra real a
  coordenar entre times que não compilam juntos ([fila-origem, F9](fila-origem.md)).
- **"Trocar a BrasilAPI pela BigBoost"** — a BrasilAPI é a **única fonte de QSA** do repositório;
  trocar sem dataset substituto levaria o KYB automatizado a zero
  ([plano-auditoria](plano-auditoria-2026-08-18.md#por-que-trocar-a-brasilapi-é-na-verdade-contratar-qsa)).
- **HPA por CPU** — o pipeline é I/O-bound em bureau; a CPU fica baixa exatamente quando a fila
  afoga ([plano-escala-horizontal](plano-escala-horizontal.md)).
- **`minReplicas: 0`** — escalar a zero mata os `@Scheduled`, e watchlist que não atualizou às
  03:00 é controle regulatório que não rodou (idem).
