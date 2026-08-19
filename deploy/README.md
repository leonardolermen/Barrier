# Verificação local em Kubernetes (kind)

Objetivo: provar que o Barrier roda em **5 réplicas atrás de um balanceador** — não que ele
sobe. Subir é fácil; o que precisa de prova é que cinco processos concorrentes processam cada
avaliação **exatamente uma vez**.

Ver o plano em [../docs/implementation/plano-escala-horizontal.md](../docs/implementation/plano-escala-horizontal.md).

## Por que kind e não o Kubernetes do Docker Desktop

`kind` é binário único, cria o cluster dentro do Docker e roda **igual no GitHub Actions**. O
Kubernetes do Docker Desktop depende de um toggle de GUI e não é reproduzível no CI — e o objetivo
aqui é justamente um teste que rode no build, não na máquina de alguém.

## Pré-requisitos

`kubectl` já está instalado. Falta o `kind` — via winget, já que `go install` exige Go:

```bash
winget install --id Kubernetes.kind
```

O binário fica em `%LOCALAPPDATA%\Microsoft\WinGet\Packages\Kubernetes.kind_*\kind.exe`. O winget
ajusta o PATH, mas **só vale em shell novo** — abra outro terminal depois de instalar.

O Docker precisa estar rodando (`docker info` responde).

## Subir

```bash
kind create cluster --name barrier
```

```bash
docker build --build-arg SERVICE=risk-engine -t barrier/risk-engine:dev .
```

```bash
docker build --build-arg SERVICE=webhook-api -t barrier/webhook-api:dev .
```

O kind tem registro próprio: a imagem local precisa ser carregada nele, senão o pod fica em
`ErrImagePull` mesmo com a imagem existindo na máquina.

```bash
kind load docker-image barrier/risk-engine:dev barrier/webhook-api:dev --name barrier
```

```bash
kubectl apply -f deploy/k8s/infra.yaml
```

```bash
kubectl apply -f deploy/k8s/risk-engine.yaml -f deploy/k8s/webhook-api.yaml
```

```bash
kubectl get pods -w
```

## O que verificar (e é aqui que está o valor)

**1. As 5 réplicas processam conjuntos disjuntos.** Script pronto:

```bash
./deploy/verify-disjuncao.sh 60
```

Ele submete N avaliações pelo Service (balanceadas entre as réplicas) e confere três coisas:
nenhuma processada duas vezes (duplicata = lease falhou), nenhuma presa em `EM_ANALISE` (órfã = o
modo de falha do ADR-0015), e a distribuição do trabalho por pod — sem esta última, uma réplica
fazendo tudo passaria no teste sem provar nada sobre concorrência.

`ConcurrentClaimIntegrationTest` já prova a disjunção com duas **threads** numa JVM; entre
**processos** nada é compartilhado (pool, cache de 1º nível do Hibernate, relógio), e é aí que
`@Version`, lease e visibilidade transacional podem divergir da intuição.

**2. Os jobs singleton rodam uma vez só** (Task 3, entregue). Para ver ao vivo sem esperar as
03:00, ligue o avaliador de alertas com ciclo curto e conte as reivindicações:

```bash
kubectl set env deployment/risk-engine BARRIER_MONITORING_ALERTS_ENABLED=true BARRIER_MONITORING_EVALUATEDELAYMS=15000
```

```bash
kubectl logs -l app=risk-engine --since=3m --prefix=true | grep "reivindicado por"
```

Esperado: ~1 reivindicação por ciclo no total (não 5). A **liderança rotaciona** entre os pods —
isso é correto: o piso do lease é zero para este job. Ver `job_locks` para quem detém:

```bash
kubectl exec deploy/postgres -- psql -U barrier -d barrier -c "SELECT * FROM job_locks;"
```

**3. Rolling update sob carga não perde requisição** — valida o `graceful shutdown` + o
`terminationGracePeriodSeconds`:

```bash
kubectl rollout restart deployment/risk-engine
```

**4. Matar um pod no meio de um lote não deixa avaliação presa** — o lease expira e outro pod
reivindica:

```bash
kubectl delete pod -l app=risk-engine --field-selector status.phase=Running --wait=false
```

**5. Todas as partições têm consumidor.** Com 6 partições e 5 pods da webhook-api, nenhum pod deve
ficar sem atribuição — era o teto silencioso que a auto-criação com 1 partição escondia.

## Derrubar

```bash
kind delete cluster --name barrier
```

## Ainda não incluído

- `ScaledObject` do KEDA (Task 4): o autoscaler precisa escalar por **profundidade de fila**, não
  por CPU — o pipeline é I/O-bound em bureau, então a CPU fica baixa exatamente quando a fila está
  afogando. Foi assim que 69.809 avaliações ficaram presas sem nenhum sinal técnico ruim.
- Ingress/LB real: o `Service` é `ClusterIP`. Para exercitar o balanceamento de fora, usar
  `kubectl port-forward` ou instalar o ingress-nginx do kind.

## Autoscaling (Task 4)

`deploy/k8s/autoscaling.yaml` **não é aplicado por padrão** — exige KEDA e Prometheus no cluster:

```bash
helm install keda kedacore/keda -n keda --create-namespace
```

O ponto do arquivo é a escolha do sinal, não o YAML: **HPA por CPU está errado aqui.** O pipeline
é I/O-bound em bureau, então a CPU fica baixa exatamente quando a fila afoga — foi assim que
69.809 avaliações ficaram presas sem nenhum indicador técnico ruim (ADR-0015). A risk-engine
escala por profundidade **e idade** da fila; a webhook-api por lag de consumo, com teto igual ao
número de partições (réplica além disso fica sem atribuição e não consome nada).
