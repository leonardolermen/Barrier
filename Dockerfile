# Build compartilhado do monorepo: `commons` é dependência dos dois serviços, então o build roda
# uma vez na raiz e cada imagem escolhe o jar pelo ARG. Um Dockerfile por serviço duplicaria o
# estágio de build e o download de dependências.
#
# Uso:
#   docker build --build-arg SERVICE=risk-engine -t barrier/risk-engine .
#   docker build --build-arg SERVICE=webhook-api -t barrier/webhook-api .

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Camada de dependências separada do código: mexer numa classe não re-baixa o mundo.
COPY pom.xml .
COPY commons/pom.xml commons/
COPY services/risk-engine/pom.xml services/risk-engine/
COPY services/webhook-api/pom.xml services/webhook-api/
RUN mvn -B -q dependency:go-offline -DskipTests

COPY commons/src commons/src
COPY services/risk-engine/src services/risk-engine/src
COPY services/webhook-api/src services/webhook-api/src

# Testes rodam no CI, não aqui: build de imagem que roda Testcontainers precisaria de Docker
# dentro do Docker. O CI é quem barra o merge.
RUN mvn -B -q package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:25-jre-alpine AS runtime

ARG SERVICE
RUN test -n "$SERVICE" || (echo "build-arg SERVICE é obrigatório (risk-engine|webhook-api)" && false)

# Usuário não-root: container que roda como root é achado de qualquer scanner de imagem, e o
# processo não precisa de privilégio nenhum.
RUN addgroup -S barrier && adduser -S -G barrier barrier
WORKDIR /app

COPY --from=build /build/services/${SERVICE}/target/*.jar /app/app.jar
RUN chown -R barrier:barrier /app
USER barrier

EXPOSE 8080

# MaxRAMPercentage, nunca -Xmx fixo: heap fixo ignora o `limits.memory` do pod, e a JVM só
# descobre que passou do limite quando o kernel a mata (OOMKilled), sem stack trace e sem GC
# tentando salvar. O percentual faz a JVM dimensionar a partir do cgroup do container.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# `exec` para o java virar PID 1: sem isso o shell é PID 1, não repassa SIGTERM, e o graceful
# shutdown do Spring nunca roda — o Kubernetes espera o grace period inteiro e mata no braço,
# derrubando requisição em voo e abandonando lease de lote no meio.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
