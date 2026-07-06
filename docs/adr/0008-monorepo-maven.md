# ADR-0008: Monorepo Maven multi-módulo

- **Status:** Aceito
- **Data:** 2026-07-04 (revisado 2026-07-06: Gradle → Maven)

## Contexto

Precisamos decidir a organização do repositório e a ferramenta de build. Os módulos
compartilham contratos de evento e o componente de outbox; o time é enxuto na fase 1 e tem
familiaridade com o ecossistema Spring/Maven.

## Decisão

Adotar um **monorepo Maven multi-módulo** (Maven Reactor), começando pela Risk Engine
([ADR-0009](0009-risk-engine-modular-monolith-first.md)):

```
barrier/
├── pom.xml                    POM pai (parent): versões, plugins, Java 25
├── commons/                   módulo compartilhado: contratos de evento, outbox, correlação
│   └── pom.xml
├── services/
│   └── risk-engine/           1º deployable (Spring Boot)
│       └── pom.xml
├── docker-compose.yml         Kafka + PostgreSQL local
└── docs/
```

- POM pai centraliza versões (`dependencyManagement`), plugins e o alvo Java 25.
- Novos deployables (ex.: Webhook API) entram como módulos sob `services/`.

## Alternativas consideradas

- **Gradle multi-módulo** — decisão anterior; trocado por Maven por preferência do time e
  familiaridade no ecossistema Spring. Substituído.
- **Um repositório por serviço (polyrepo)** — melhor isolamento, mas atrito alto para
  compartilhar contratos com time enxuto. Reconsiderar quando os times amadurecerem.

## Consequências

- **Positivas:** compartilhamento fácil de `commons`; refactors atômicos cross-módulo; um
  `docker-compose` sobe tudo; build Reactor conhecido pelo time.
- **Negativas / custos:** build cresce com o número de módulos; acoplamento acidental via
  `commons` se não houver disciplina; CI se beneficia de build seletivo por módulo.
- **Riscos e mitigações:** manter `commons` mínimo (contratos e utils genéricos); evitar
  regra de negócio compartilhada lá.
