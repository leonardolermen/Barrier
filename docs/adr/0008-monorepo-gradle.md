# ADR-0008: Monorepo Gradle multi-módulo

- **Status:** Proposto
- **Data:** 2026-07-04

## Contexto

Com microserviços desde o início, precisamos decidir a organização de repositórios. Os
serviços compartilham contratos de evento e utilidades, e o time é enxuto na fase 1.

## Decisão (proposta)

Adotar um **monorepo Gradle multi-módulo**:

```
barrier/
├── commons/                módulo compartilhado: contratos de evento, correlação, utils
├── services/
│   ├── assessment-api/
│   ├── identity/
│   ├── screening/
│   ├── risk-scoring/
│   ├── case-management/
│   ├── webhook-dispatcher/
│   └── audit/
├── docker-compose.yml      Kafka + PostgreSQL local
└── docs/
```

## Alternativas consideradas

- **Um repositório por serviço (polyrepo)** — melhor isolamento e ownership independente,
  mas atrito alto para compartilhar contratos e evoluir tudo junto com time enxuto.
  Reconsiderar quando os serviços/times amadurecerem.

## Consequências

- **Positivas:** compartilhamento fácil de `commons`; refactors atômicos cross-serviço; um
  `docker-compose` sobe tudo; onboarding simples.
- **Negativas / custos:** build pode crescer; acoplamento acidental via `commons` se não
  houver disciplina; CI precisa build seletivo por módulo.
- **Riscos e mitigações:** manter `commons` mínimo (só contratos e utils genéricos); evitar
  colocar regra de negócio compartilhada lá.

## Pendências

Confirmar com o time antes de mover para "Aceito", junto da decisão sobre o corte inicial
(fatia vertical fina vs. os 6 serviços de uma vez).
