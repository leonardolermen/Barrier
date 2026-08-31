# Teste de carga — risk-engine

Arnês de carga em [k6](https://k6.io). Roda à mão; **ainda não está no CI** (ver item "Chaos e
carga" no [backlog de produto](../../../../../docs/product/backlog.md)).

O que ele mede e por que existe está na
[ADR-0015](../../../../../docs/adr/0015-ingestao-em-massa-faixa-separada.md).

## Rodar

Suba a infra (`docker compose up -d`) e o serviço, e emita uma credencial — em dev o
`DevApiKeyIssuer` imprime uma no log da subida quando não há nenhuma ativa.

```bash
docker run --rm -i -v "$PWD/services/risk-engine/src/test/load:/scripts" -e BASE_URL=http://host.docker.internal:8080 -e API_KEY=brr_xxx grafana/k6 run /scripts/assessments.js
```

Em Linux, troque `host.docker.internal` por `localhost` e acrescente `--network host`.

## O que o script faz

Ramp em degraus (10 → 50 → 150 VUs, ~5 min) de `POST /v1/assessments`, com 1 em cada 5 iterações
consultando o `GET` logo em seguida — que é o que um cliente real faz enquanto espera o
processamento assíncrono. Os CPFs são gerados com dígitos verificadores válidos: `Cpf.java`
rejeita qualquer outra coisa, e um teste que só exercita o caminho de `400` não mede nada.

Usa **CPF**, não CNPJ, de propósito: CPF cai no `FakeCpfBureauProvider` e não toca a rede. CNPJ
chamaria a BrasilAPI de verdade — martelar uma API pública gratuita com teste de carga não se faz.

## Ler o resultado

Os thresholds do script cobrem só a borda HTTP. **O número que importa não está no relatório do
k6**: a ingestão responde `202` e o trabalho real acontece depois, no `AssessmentProcessor`. Meça
os dois lados, ou o teste conclui que está tudo bem enquanto a fila cresce sem limite:

```bash
docker exec barrier-postgres psql -U barrier -d barrier -c "select status, count(*) from assessments group by status order by 2 desc;"
```

Na medição de 2026-08-10 a ingestão sustentou 292 req/s com 0% de erro enquanto o processamento
drenava ~12,5/s — 69.809 avaliações ficaram presas em `EM_ANALISE`.

> O script cria dezenas de milhares de avaliações reais no banco apontado. Não rode contra base
> que você não possa sujar.
