# Diagramas

| Arquivo | Descrição |
|---------|-----------|
| [arquitetura-atual.svg](arquitetura-atual.svg) | **Estado implementado**: Risk Engine (módulos assessment/identity/screening/risk) + Kafka + Webhook API + schemas Postgres. |
| [topologia-mvp.svg](topologia-mvp.svg) | Topologia-alvo de microserviços (visão de longo prazo): borda, Kafka, serviços de domínio, webhook e auditoria. |
| [camadas-por-servico.svg](camadas-por-servico.svg) | Padrão de camadas clássicas (controller/service/repository/client) por serviço, na visão-alvo. |
| [hexagonal-nao-usado.svg](hexagonal-nao-usado.svg) | Referência da arquitetura hexagonal **considerada e descartada** ([ADR-0002](../adr/0002-classic-layered-architecture.md)). |

O primeiro diagrama reflete o que existe hoje; os dois seguintes são a visão-alvo. Os SVGs são
autocontidos (cores embutidas) e renderizam direto no GitHub e na IDE.

> **⚠️ `arquitetura-atual.svg` está desatualizado.** Desenha os 4 módulos originais
> (assessment/identity/screening/risk) de 16. Os módulos reais estão em
> [domain-contexts.md](../architecture/domain-contexts.md) — **essa é a fonte de verdade**.
> Redesenhar o SVG é item do [backlog de produto](../product/backlog.md#5--operação-e-prova-de-escala).
>
> **Correção (2026-08-31):** este aviso dizia que o rodapé citava módulos que *"nunca existiram"*
> (`geoip`/`device`/`credit`/`history`). Não é o caso — eles existem, nas branches
> `feat/network-signals`, `feat/phone-email-signals` e `feat/history-credit-signals`, que nunca
> foram integradas. O diagrama foi desenhado a partir de trabalho que não entrou, o que é um
> problema diferente e está registrado no
> [backlog](../product/backlog.md#trabalho-órfão--três-branches-nunca-integradas).
