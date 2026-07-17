# Padrões de código e design patterns — Barrier

Fonte única da verdade sobre **como** escrevemos código no Barrier. Todo módulo/serviço
segue isto. A [skill de implementação](../../.claude/skills/barrier-implementation/SKILL.md)
e o `CLAUDE.md` apontam para cá.

## Stack e versões

| Item        | Padrão                                             |
|-------------|-----------------------------------------------------|
| Linguagem   | Java 25 (LTS) — records, pattern matching, virtual threads |
| Framework   | Spring Boot 4.0 (Spring Framework 7)                |
| Build       | Maven (Reactor), monorepo multi-módulo              |
| Banco       | PostgreSQL + Flyway (migrations versionadas)        |
| Mensageria  | Apache Kafka (Spring for Apache Kafka)              |
| Mapeamento  | Mappers escritos à mão (`XyzEntityMapper`/`XyzDtoMapper`, classes `final` com métodos estáticos) — MapStruct está no `pom.xml` mas ainda sem uso real |
| Testes      | JUnit 5, Mockito, AssertJ, Testcontainers, ArchUnit |
| API docs    | Nenhuma ainda — springdoc-openapi previsto para a Fase 5 (ver [risk-engine-plan.md](risk-engine-plan.md)) |
| Observ.     | Micrometer + logs estruturados (JSON)               |

## Regra de camadas (clássica)

```
controller → service → repository
                    ↘ client (integração externa, atrás de interface)
```

Regras invioláveis (validadas por ArchUnit):

1. `controller` nunca acessa `repository` direto — sempre via `service`.
2. `service` acessa integrações externas **apenas por interface** do pacote `client`,
   nunca por SDK/HTTP direto.
3. `controller` fala DTO; `service`/`domain` falam objetos de domínio; `repository` fala
   entidade. Conversão por mapper dedicado nas bordas (hoje escrito à mão).
4. Entidades JPA **não** vazam para o `controller`.
5. Sem regra de negócio em `controller` nem em `repository`.

## Design patterns adotados (e onde)

| Pattern                     | Onde aplicamos                                             |
|-----------------------------|-----------------------------------------------------------|
| **Layered architecture**    | Estrutura de todo serviço                                  |
| **Repository**              | Persistência via Spring Data JPA                          |
| **Transactional Outbox**    | Toda publicação de evento (grava outbox na mesma tx)      |
| **Gateway / Adapter**       | Integrações externas: `BureauProvider`, `WatchlistProvider`, `GeoIpProvider`, `PhoneProvider`, `EmailProvider`, `CreditScoreProvider` (interface + impl, stub em dev) |
| **Strategy**                | Regras de risco (`RiskRule`) e de screening (`ScreeningRule`) — adicionar fonte = adicionar regra, sem tocar no motor |
| **Pipeline / Orchestrator** | `AssessmentProcessor` orquestra identity → screening → risk → gate de completude |
| **Value Object**            | `Cpf`, `Cnpj`, `AssessmentId` (records validados)         |
| **Factory**                 | Construção de eventos de domínio e agregados              |
| **DTO + Mapper**            | Fronteiras (mapper dedicado por módulo, escrito à mão)     |
| **Idempotency key**         | Consumidores/entregas: `assessmentId + eventType`         |

Não usamos pattern por pattern — cada um resolve um problema concreto acima. Nada de
abstração especulativa.

## Modelagem de domínio

- Use **records** para value objects e DTOs imutáveis.
- Validação de VO no construtor compacto (ex.: `Cpf` valida dígitos verificadores).
- Enums para estados e classificações (`RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }`,
  `AssessmentStatus { EM_ANALISE, APROVADO, REPROVADO, EM_REVISAO }`).
- Estados de agregado mudam por métodos de domínio, não por setters soltos.

## Persistência e Outbox

- Uma **migration Flyway** por mudança de schema (`V001__...sql`), nunca editar migration
  já aplicada.
- Cada serviço é dono do seu schema; sem tabelas compartilhadas.
- **Outbox obrigatório** para eventos: na mesma `@Transactional` que altera estado, grave
  em `outbox`. Um relay agendado publica no Kafka e marca como enviado.
- Componente de outbox mora no `commons` e é reutilizado (não reimplementar por serviço).

## Kafka

- Tópicos nomeados por evento: `barrier.assessment.completed`.
- Chave da mensagem = `assessmentId` (garante ordem por avaliação).
- Todo evento carrega envelope: `eventId`, `assessmentId` (correlation), `occurredAt`,
  `version`, `payload`.
- Consumidores **idempotentes** (descartar `eventId` já processado). Assumir at-least-once.

## Tratamento de erro

- Exceções de domínio específicas (`AssessmentNotFoundException`,
  `InvalidDocumentException`), traduzidas por `@RestControllerAdvice` para um corpo de erro
  padrão (RFC 7807 `application/problem+json`).
- Nunca engolir exceção; logar com `assessmentId` no contexto (MDC).
- Falha de integração externa (bureau/watchlist) não derruba a avaliação: registra o
  resultado como indisponível e a regra de risco decide o que fazer.

## Testes (pirâmide)

- **Unitário** — regra de negócio e VOs, sem Spring. Mockito para colaboradores. AssertJ.
- **Integração** — `@SpringBootTest` com **Testcontainers** (Postgres + Kafka reais).
- **Arquitetura** — **ArchUnit** valida as regras de camada acima.
- **Contrato** — payloads de evento versionados têm teste de serialização.
- Cobertura foca em regra de risco e outbox; getters/DTOs não precisam de teste dedicado.
- Todo bug corrigido nasce com um teste que o reproduz.

## API

- REST com verbos e status corretos: `POST /assessments` → `202`, `GET /assessments/{id}`
  → `200`/`404`.
- Contrato ainda não documentado em OpenAPI (springdoc é Fase 5) — usar a
  [collection Postman](../api/README.md) como referência viva. DTO de request validado com
  Bean Validation.
- Versionamento por caminho quando quebrar contrato (`/v1/...`).
- Endpoints internos/admin (config por tenant, registry de regras, histórico) usam a mesma
  pré-auth por header do resto da API — sem gate de admin-auth dedicado ainda.

## Segurança e LGPD

- Sem dado sensível em log (mascarar CPF/CNPJ: `***.***.**9-00`).
- Segredos por variável de ambiente / config server, nunca no código.
- Guardar o mínimo necessário nesta fase (somos operador) — ver
  [compliance.md](../architecture/compliance.md).

## Convenções de projeto

- Pacote raiz: `com.barrier.<contexto>`.
- Nomes em inglês no código; comentários e docs em PT quando ajudar o time.
- Commits: `tipo(escopo): descrição` (`feat(risk): ...`, `test(identity): ...`).
- Formatação automática (Spotless). Sem warning de compilação no CI.
- Um PR = uma unidade coerente; verde no CI antes de merge.
