# Diagramas

| Arquivo | Descrição |
|---------|-----------|
| [arquitetura-atual.svg](arquitetura-atual.svg) | **Estado implementado**: Risk Engine (módulos assessment/identity/screening/risk) + Kafka + Webhook API + schemas Postgres. |
| [topologia-mvp.svg](topologia-mvp.svg) | Topologia-alvo de microserviços (visão de longo prazo): borda, Kafka, serviços de domínio, webhook e auditoria. |
| [camadas-por-servico.svg](camadas-por-servico.svg) | Padrão de camadas clássicas (controller/service/repository/client) por serviço, na visão-alvo. |
| [hexagonal-nao-usado.svg](hexagonal-nao-usado.svg) | Referência da arquitetura hexagonal **considerada e descartada** ([ADR-0002](../adr/0002-classic-layered-architecture.md)). |

O primeiro diagrama reflete o que existe hoje; os dois seguintes são a visão-alvo. Os SVGs são
autocontidos (cores embutidas) e renderizam direto no GitHub e na IDE.
