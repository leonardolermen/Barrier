# ADR-0002: Camadas clássicas por serviço

- **Status:** Aceito
- **Data:** 2026-07-04

## Contexto

Cada microserviço precisa de um estilo interno de organização. O time tem forte domínio de
arquitetura em camadas clássicas (`controller → service → repository`). Foi avaliada a
alternativa hexagonal (ports & adapters).

## Decisão

Vamos usar **camadas clássicas** em cada serviço:

```
com.kyc.<contexto>
├── controller     REST + listeners Kafka
├── service        regra de negócio
├── repository     JPA
├── domain/model   entidades e enums
├── dto            request/response + contratos de evento
├── client         integrações externas atrás de interface
└── config         Kafka, security, beans
```

Disciplina única obrigatória: o `service` acessa integrações externas (bureaus, watchlists)
**por interface** no pacote `client`, nunca pelo SDK direto.

## Alternativas consideradas

- **Hexagonal (ports & adapters)** — isola melhor o domínio de infra e é excelente em
  domínios regulatórios complexos, mas adiciona cerimônia. Em microserviços já pequenos e
  com fronteira bem definida, o ganho não compensa o custo cognitivo para o time. Descartado.

## Consequências

- **Positivas:** produtividade imediata do time; código familiar; menos abstração.
- **Negativas / custos:** menor isolamento do domínio frente à infra do que na hexagonal.
- **Mitigações:** a regra da interface no pacote `client` preserva testabilidade e permite
  trocar bureaus sem tocar na regra de negócio — captura o principal benefício da hexagonal
  a custo baixo.
