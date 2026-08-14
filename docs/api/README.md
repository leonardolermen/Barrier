# API — Risk Engine (Postman)

Collection e environment para exercitar o Barrier localmente, **numerados em sequência**: as
pastas `00` a `08` são o passo a passo do cadastro ponta a ponta, e é para rodar em ordem.

## Arquivos

- `barrier-risk-engine.postman_collection.json` — 24 requests em 9 pastas numeradas.
- `barrier-local.postman_environment.json` — variáveis para `localhost`.
- `../../tools/webhook-receiver.py` — receptor local que confere a assinatura HMAC.
- `../../tools/ngrok.yml` — túnel nomeado que expõe o receptor.

## Preparo

### 1. Infra e apps

```sh
docker compose up -d
JAVA_HOME=~/.jdks/corretto-25.0.3 ./mvnw -pl services/risk-engine -am spring-boot:run
```

Em outro terminal, a Webhook API:

```sh
JAVA_HOME=~/.jdks/corretto-25.0.3 ./mvnw -pl services/webhook-api -am spring-boot:run
```

### 2. Receptor de webhook

```sh
python3 tools/webhook-receiver.py
```

Sobe em `http://localhost:9000`. Sem `BARRIER_WEBHOOK_SECRET` no ambiente ele imprime o
payload mas marca a assinatura como **não verificada** — o segredo sai no passo **01.1** da
collection, e o script de teste imprime no console do Postman o `export` pronto para colar.

### 3. Túnel do ngrok

```sh
ngrok start barrier-webhook \
  --config "$HOME/Library/Application Support/ngrok/ngrok.yml" \
  --config tools/ngrok.yml
```

Dois `--config` de propósito: o authtoken fica no config global do agente e **não** entra no
repositório; `tools/ngrok.yml` traz só a definição do túnel.

Copie a URL `https://….ngrok-free.app` que o agente imprime para a variável `ngrokUrl` do
environment, **sem barra no final**. O inspetor das entregas fica em <http://localhost:4040> —
mostra corpo, headers (inclusive `X-Barrier-Signature`) e permite **Replay** sem criar
avaliação nova.

> **O que fica exposto à internet:** apenas a porta 9000, o receptor. A Risk Engine (8080) e a
> Webhook API (8082) continuam inacessíveis de fora. O receptor é deliberadamente burro — o que
> chega vira texto na tela e nada mais. Ainda assim, derrube o túnel quando terminar: URL de
> ngrok gratuito é pública e adivinhável.

### 4. Postman

**Import** os dois arquivos, selecione o environment **Barrier — Local** e preencha:

| Variável | Como obter |
|---|---|
| `apiKey` | log da risk-engine no startup — procure por **"API key de DESENVOLVIMENTO"** |
| `adminKey` | header `X-Admin-Key` dos endpoints administrativos |
| `ngrokUrl` | URL HTTPS impressa pelo `ngrok start` |
| `tenantId` | `default` (o tenant semeado); só mude se criou outro |

`webhookSecret` e `assessmentId` são preenchidas sozinhas pelos scripts de teste.

## O roteiro

| Pasta | O que exercita |
|---|---|
| **00 · Preparo** | health dos dois deployables |
| **01 · Webhook** | registro do endpoint, segredo, rotação com sobreposição |
| **02 · Cadastro** | `SubjectProfile` da CMN 4.753, progressivo |
| **03 · PF automático** | submissão → `APROVADO` → **primeira entrega no receptor** |
| **04 · EDD** | PEP cai em `EM_REVISAO` → decisão humana → **segunda entrega** |
| **05 · PJ** | KYB de 1º grau via BrasilAPI (exige rede) |
| **06 · Histórico** | evento interno mudando o score sem consulta externa |
| **07 · Config por tenant** | override de parâmetro de regra de apetite |
| **08 · Registry** | kill switch global de uma família de regra |

**A ordem importa em dois pontos, e só neles:**

- **01 antes de 03.** A entrega é disparada pela conclusão da avaliação. Sem endpoint
  registrado o webhook-api apenas loga, e a primeira avaliação não chega em lugar nenhum.
- **02 antes de 03.** Sem cadastro, o gate de completude rebaixa `APROVADO` → `EM_REVISAO`
  com um fator listando os campos que faltam. Não é reprovação — é o gate da CMN 4.753. Rode
  o 03 sem o 02 se quiser ver isso acontecer.

Dentro da pasta 04, rode **04.3 ou 04.4**, nunca os dois: a segunda decisão sobre a mesma
avaliação responde 409, porque `Assessment.decide` exige `EM_REVISAO`.

## Rodar sem Postman (newman)

```sh
npx newman run docs/api/barrier-risk-engine.postman_collection.json \
  -e docs/api/barrier-local.postman_environment.json
```

⚠️ A collection **não** é uma suíte de regressão: o 04.4 conflita com o 04.3 por desenho, e o
03.2 pode pegar a avaliação ainda em `EM_ANALISE` (o processamento é assíncrono). Para newman,
rode pasta a pasta com `--folder`.

## Notas

- **Swagger/OpenAPI** ainda não está ligado (springdoc está previsto para a Fase 5; springdoc
  3.x + Spring Boot 4 precisa de validação de compatibilidade).
- Ao editar `reason` na decisão, garanta corpo **UTF-8 válido** — alguns terminais no Windows
  reescrevem o encoding e a API rejeita com `400 Invalid UTF-8` (não é bug da app).
- O segredo do webhook aparece **uma vez**, no registro e na rotação. O `GET` só informa
  `secretConfigured`. Perdeu, rotacione (01.4).
