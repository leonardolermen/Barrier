#!/usr/bin/env bash
# Prova de disjunção entre PROCESSOS: N avaliações submetidas a 5 réplicas atrás de um Service,
# e cada uma precisa ser processada exatamente uma vez.
#
# Por que este teste existe, sendo que ConcurrentClaimIntegrationTest já prova o SKIP LOCKED:
# aquele roda com duas THREADS numa JVM, compartilhando pool de conexões, cache de primeiro nível
# do Hibernate e relógio. Entre PROCESSOS distintos nada disso é compartilhado, e é aí que
# `@Version`, lease e visibilidade transacional podem divergir da intuição.
#
# Uso: ./deploy/verify-disjuncao.sh [quantidade]
set -euo pipefail

TOTAL="${1:-60}"
NS="${NS:-default}"

psql() { kubectl exec -n "$NS" deploy/postgres -- psql -U barrier -d barrier -tAc "$1"; }

echo "==> Réplicas de pé"
kubectl get pods -n "$NS" -l app=risk-engine --no-headers | awk '{print "    " $1, $3}'

echo "==> Emitindo credencial do tenant de teste"
# A migration não semeia chave conhecida (decisão de segurança: credencial não nasce em migration).
POD=$(kubectl get pods -n "$NS" -l app=risk-engine -o jsonpath='{.items[0].metadata.name}')
API_KEY=$(kubectl exec -n "$NS" "$POD" -- sh -c '
  curl -s -X POST "http://localhost:8080/v1/tenants/default/api-keys" \
    -H "X-Admin-Key: $ADMIN_API_KEY" -H "Content-Type: application/json" \
    -d "{\"name\":\"verify-disjuncao\"}"' | sed -n 's/.*"presentedValue":"\([^"]*\)".*/\1/p')

if [ -z "$API_KEY" ]; then
  echo "!! não foi possível emitir credencial — verifique ADMIN_API_KEY no Secret" >&2
  exit 1
fi

echo "==> Submetendo $TOTAL avaliações através do Service (balanceadas entre as 5 réplicas)"
BEFORE=$(psql "SELECT count(*) FROM assessments;")

kubectl run -n "$NS" carga-disjuncao --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- \
  sh -c "
    for i in \$(seq 1 $TOTAL); do
      # CPF sintético válido para o bureau simulado; documento distinto por iteração evita que a
      # idempotência de intake (Idempotency-Key ausente) ou o dedup por subject mascarem o teste.
      curl -s -o /dev/null -X POST http://risk-engine:8080/v1/assessments \
        -H 'Authorization: Bearer $API_KEY' -H 'Content-Type: application/json' \
        -d '{\"documentType\":\"CPF\",\"document\":\"11144477735\",\"name\":\"Teste Disjuncao\"}'
    done
  " >/dev/null 2>&1 || true

echo "==> Aguardando o pipeline drenar"
for _ in $(seq 1 60); do
  PENDING=$(psql "SELECT count(*) FROM assessments WHERE status = 'EM_ANALISE';")
  [ "$PENDING" -eq 0 ] && break
  sleep 5
done

echo
echo "===================== RESULTADO ====================="
AFTER=$(psql "SELECT count(*) FROM assessments;")
echo "avaliações criadas:              $((AFTER - BEFORE))"

# 1. DUPLICATA: duas réplicas processando a mesma avaliação produziriam mais de um risk_score
#    para o mesmo assessment_id. É o que o lease + SKIP LOCKED existem para impedir.
DUP=$(psql "
  SELECT count(*) FROM (
    SELECT assessment_id FROM risk_scores GROUP BY assessment_id HAVING count(*) > 1
  ) d;")
echo "avaliações processadas 2x+:      $DUP   (esperado: 0)"

# 2. ÓRFÃ: avaliação que ficou em EM_ANALISE sem ninguém reivindicar — o oposto da duplicata, e o
#    modo de falha que o teste de carga do ADR-0015 produziu (69.809 presas, sem erro).
ORFA=$(psql "SELECT count(*) FROM assessments WHERE status = 'EM_ANALISE';")
echo "avaliações presas em EM_ANALISE: $ORFA   (esperado: 0)"

# 3. DISTRIBUIÇÃO: se uma réplica só fez tudo, o teste passou sem provar nada sobre concorrência.
echo
echo "Distribuição do trabalho entre as réplicas (log de conclusão por pod):"
for p in $(kubectl get pods -n "$NS" -l app=risk-engine -o jsonpath='{.items[*].metadata.name}'); do
  n=$(kubectl logs -n "$NS" "$p" --since=10m 2>/dev/null | grep -c "Avaliação .* concluída" || true)
  echo "    $p -> $n"
done

echo
if [ "$DUP" -eq 0 ] && [ "$ORFA" -eq 0 ]; then
  echo "OK: disjunção entre processos confirmada — sem duplicata e sem órfã."
else
  echo "FALHOU: ver os números acima."
  exit 1
fi
