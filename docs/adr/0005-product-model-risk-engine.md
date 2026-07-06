# ADR-0005: Modelo de produto — motor de risco (A), evoluindo para plataforma (B)

- **Status:** Aceito
- **Data:** 2026-07-04

## Contexto

Duas visões de produto eram possíveis:

- **Modelo A — motor de risco (B2B):** o cliente já é dono do cadastro e nos envia dados de
  um cliente existente; devolvemos a decisão de risco. Somos **operador** (LGPD).
- **Modelo B — plataforma completa (system of record):** guardamos cadastro, documentos,
  biometria e registros por 10 anos. Somos **controlador** de dado sensível.

## Decisão

Vamos **começar pelo Modelo A** e **desenhar a arquitetura já prevendo a evolução para B**.
Os serviços do motor de risco (Onboarding/Identity/Screening/Risk/Case) são subconjunto da
plataforma completa; a fase 2 **adiciona** módulos de acervo (documentos, biometria,
retenção, direitos do titular), sem reescrever.

## Alternativas consideradas

- **Modelo B desde o início** — maior valor entregue, mas escopo e exposição regulatória/
  LGPD muito maiores no dia 1 (controlador de biometria), time-to-market longo. Descartado
  para o começo.
- **Modelo A puro, sem prever B** — mais simples, mas arriscaria decisões que travariam a
  evolução. Descartado em favor de "A com arquitetura pronta para B".

## Consequências

- **Positivas:** menor exposição regulatória e de dado sensível no início; venda mais rápida
  a clientes que já têm onboarding; caminho de evolução preservado.
- **Negativas / custos:** algumas decisões (auditoria, retenção, contratos de evento) são
  tomadas cedo pensando na fase 2, o que adiciona rigor mesmo no MVP.
- **Mitigações:** documentar claramente o que é fase 1 vs fase 2 (ver
  [domain-contexts.md](../architecture/domain-contexts.md) e
  [compliance.md](../architecture/compliance.md)).
