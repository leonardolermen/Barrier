# API — Risk Engine (Postman)

Collection e environment do Postman para exercitar a Risk Engine localmente.

## Arquivos

- `barrier-risk-engine.postman_collection.json` — as requests (Health, Assessments, Subjects).
- `barrier-local.postman_environment.json` — variáveis para `http://localhost:8080`.

## Como usar

1. Suba a infra e a app:
   ```sh
   docker compose up -d
   JAVA_HOME=~/.jdks/corretto-25.0.3 ./mvnw -pl services/risk-engine -am spring-boot:run
   ```
2. No Postman: **Import** os dois arquivos e selecione o environment **Barrier — Local**.
3. Rode nesta ordem (a collection captura o `assessmentId` automaticamente):
   - **Assessments → Submit — CPF** → depois **Get assessment** (vira `APROVADO`).
   - **Submit — CPF PEP** → **Get assessment** (`EM_REVISAO`) → **Decision — APPROVE** (`APROVADO`, com trilha).
   - **Submit — CNPJ** → **Get assessment** (traz os `factors` das regras de PJ; exige rede para a BrasilAPI).

## Variáveis

| Variável | Default | Uso |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | host da API |
| `clientId` | `default` | header `X-Client-Id` (tenant) |
| `assessmentId` | *(vazio)* | preenchida pelos scripts de teste após o POST |
| `document` | `11144477735` | usado no GET de subject |

## Rodar sem Postman (newman)

```sh
npx newman run docs/api/barrier-risk-engine.postman_collection.json \
  -e docs/api/barrier-local.postman_environment.json
```

## Notas

- **Swagger/OpenAPI** ainda não está ligado (springdoc está previsto para a Fase 5;
  springdoc 3.x + Spring Boot 4 precisa de validação de compatibilidade).
- Ao editar `reason` na decisão, garanta corpo **UTF-8 válido** — alguns terminais no Windows
  reescrevem o encoding e a API rejeita com `400 Invalid UTF-8` (não é bug da app).
