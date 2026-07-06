# ADR-0001: Topologia de microserviços

- **Status:** Substituído parcialmente por [ADR-0009](0009-risk-engine-modular-monolith-first.md)
- **Data:** 2026-07-04

> **Nota:** a decisão de "microserviços desde o dia 1" foi revista. O núcleo de risco
> começa como monólito modular (Risk Engine API) com split incremental — ver
> [ADR-0009](0009-risk-engine-modular-monolith-first.md). A visão de longo prazo em
> microserviços permanece válida como destino.

## Contexto

O domínio de KYC/PLD-FT tem contextos com ciclos de vida, escalas e times potencialmente
distintos (verificação de identidade depende de bureaus lentos; screening consulta
watchlists; case management é interativo com analistas). O sistema precisa evoluir de um
motor de risco (fase 1) para uma plataforma completa (fase 2).

## Decisão

Vamos adotar **microserviços desde o início**, um por bounded context, cada um dono do seu
schema, comunicando-se apenas por eventos.

## Alternativas consideradas

- **Monolito modular** — mais rápido de operar no começo, mas a evolução para fase 2 e a
  necessidade de escalar contextos de forma independente (ex.: screening) favorecem a
  separação desde já. Descartado por decisão do time.

## Consequências

- **Positivas:** escalabilidade independente; fronteiras fortes; deploy isolado; caminho
  natural para a fase 2.
- **Negativas / custos:** complexidade operacional (observabilidade, infra, consistência
  distribuída) desde o dia 1; exige disciplina de contratos de evento.
- **Mitigações:** monorepo para reduzir atrito de desenvolvimento ([ADR-0008](0008-monorepo-gradle.md));
  `docker-compose` local; começar por uma fatia vertical fina antes dos 6 serviços.
