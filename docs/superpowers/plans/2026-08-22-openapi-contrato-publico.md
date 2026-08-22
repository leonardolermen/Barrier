# OpenAPI — contrato público da API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publicar o contrato OpenAPI dos dois serviços, com a superfície administrativa **fora** do documento público, e um teste reflexivo que falha quando uma rota de negócio nasce sem documentação.

**Architecture:** springdoc 3.0.0 gera o spec por introspecção dos controllers. Dois `GroupedOpenApi`: o grupo `parceiro` (publicável) casa só as rotas com escopo de tenant; o grupo `admin` fica separado e nunca é publicado. A UI é desligada em `prod` — o artefato de produto é o arquivo estático do spec, não uma UI viva no host da API. A cobertura é garantida por um teste que enumera os controllers via ArchUnit (mesmo mecanismo do `ApiRouteCoverageTest`) e exige que cada rota de tenant apareça no spec.

**Tech Stack:** Java 25 · Spring Boot 4.0 · springdoc-openapi 3.0.0 · JUnit 5 · ArchUnit · AssertJ · Testcontainers

**Spec:** [docs/implementation/plano-produto-api-first.md](../../implementation/plano-produto-api-first.md) — Fase 1, item "OpenAPI gerado, versionado e publicado"

## Global Constraints

- **springdoc 3.0.0** — a linha 2.x é para Boot 3 e **não** serve. Versão já verificada como resolvível em 2026-08-22 (`dependency:get` baixou `springdoc-openapi-starter-webmvc-ui:3.0.0` e suas transitivas).
- **`JAVA_HOME` antes do `mvnw`:** `C:\Users\leona\.jdks\corretto-25.0.3`
- **Docker de pé** para todo teste de integração — sem ele a suíte fica verde só na aparência.
- **`./mvnw spotless:apply` NÃO roda no JDK 25** (google-java-format quebra). Formatar à mão.
- **Nenhuma rota administrativa** pode aparecer no grupo `parceiro`. Hoje são: `/v1/risk-rules`, `/v1/tenants/**`, `/v1/webhook-endpoints/**`.
- **Nada de PII em exemplo de documentação.** Usar os CPFs sintéticos do sandbox (prefixo `999`), nunca documento real.
- Comentários e mensagens em **português**, no tom do repositório: explicar *por quê*, não *o quê*.

---

### Task 1: springdoc na risk-engine, com a UI desligada em prod

**Files:**
- Modify: `pom.xml` (dependencyManagement — fixar a versão num lugar só)
- Modify: `services/risk-engine/pom.xml` (remover o comentário "Fase 5", adicionar a dependência)
- Modify: `services/risk-engine/src/main/resources/application-prod.yml` (desligar a UI)
- Test: `services/risk-engine/src/test/java/com/barrier/riskengine/web/OpenApiDocumentIntegrationTest.java`

**Interfaces:**
- Consumes: nada de tarefas anteriores.
- Produces: o endpoint `GET /v3/api-docs/parceiro` (grupo criado na Task 2; nesta tarefa ainda é o spec default em `GET /v3/api-docs`), servido na porta de negócio.

- [ ] **Step 1: Escrever o teste que falha**

`services/risk-engine/src/test/java/com/barrier/riskengine/web/OpenApiDocumentIntegrationTest.java`:

```java
package com.barrier.riskengine.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * O contrato público existe e é servido.
 *
 * <p>Em A o produto <b>é</b> a integração: um spec que não sobe não é documentação atrasada, é
 * produto quebrado. Por isso a verificação é de integração e não unitária — o que importa não é a
 * dependência estar no pom, é o documento sair pela porta que o parceiro alcança.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OpenApiDocumentIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  @Test
  void serveOContratoNaPortaDeNegocio() {
    String corpo =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/v3/api-docs")
            .retrieve()
            .body(String.class);

    assertThat(corpo).as("spec vazio ou ausente").isNotBlank();
    assertThat(corpo).contains("/v1/assessments");
  }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
JAVA_HOME="C:/Users/leona/.jdks/corretto-25.0.3" ./mvnw -pl services/risk-engine -am test -Dtest=OpenApiDocumentIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Esperado: FALHA. O `/v3/api-docs` responde 404 — a dependência não existe ainda.

- [ ] **Step 3: Fixar a versão no pom raiz**

Em `pom.xml`, dentro de `<dependencyManagement><dependencies>`:

```xml
<!--
    springdoc NAO e gerenciado pelo BOM do Boot 4 (mesma situacao do testcontainers-bom).
    A linha 3.x e a que suporta Boot 4; a 2.x e para Boot 3 e falha em runtime.
-->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.0.0</version>
</dependency>
```

- [ ] **Step 4: Adicionar a dependência na risk-engine**

Em `services/risk-engine/pom.xml`, substituir o comentário `<!-- docs de API (springdoc 3.x) entram na Fase 5 -->` por:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
</dependency>
```

- [ ] **Step 5: Rodar o teste e confirmar que passa**

```bash
JAVA_HOME="C:/Users/leona/.jdks/corretto-25.0.3" ./mvnw -pl services/risk-engine -am test -Dtest=OpenApiDocumentIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Esperado: PASSA.

- [ ] **Step 6: Desligar a UI em produção**

Em `services/risk-engine/src/main/resources/application-prod.yml`, acrescentar:

```yaml
springdoc:
  # UI desligada em prod, spec ligado. O artefato de produto e o ARQUIVO do spec, publicado no site
  # de documentacao; uma UI viva no host da API e superficie extra servindo o mesmo conteudo, no
  # mesmo raciocinio que tirou o /actuator da porta de negocio.
  swagger-ui:
    enabled: false
```

- [ ] **Step 7: Rodar a suíte da risk-engine inteira**

```bash
JAVA_HOME="C:/Users/leona/.jdks/corretto-25.0.3" ./mvnw -pl services/risk-engine -am test
```

Esperado: BUILD SUCCESS. (Baseline antes desta tarefa: 655 testes na risk-engine.)

- [ ] **Step 8: Commit**

```bash
git add pom.xml services/risk-engine/pom.xml services/risk-engine/src
git commit -m "feat(api): contrato OpenAPI servido pela risk-engine"
```

---

### Task 2: Separar o grupo do parceiro do grupo administrativo

O spec default documenta **tudo**, inclusive `/v1/tenants/{id}/api-keys` e `/v1/risk-rules`. Publicar isso entrega ao mundo o mapa da superfície administrativa — o mesmo tipo de vazamento que motivou tirar o `/actuator` da porta de negócio.

**Files:**
- Create: `services/risk-engine/src/main/java/com/barrier/riskengine/web/OpenApiConfig.java`
- Test: `services/risk-engine/src/test/java/com/barrier/riskengine/web/OpenApiDocumentIntegrationTest.java` (modificar — acrescentar casos)

**Interfaces:**
- Consumes: springdoc configurado na Task 1.
- Produces: `GET /v3/api-docs/parceiro` (público) e `GET /v3/api-docs/admin` (interno). A Task 3 consome o caminho `/v3/api-docs/parceiro`.

- [ ] **Step 1: Escrever os testes que falham**

Acrescentar a `OpenApiDocumentIntegrationTest`:

```java
  private String spec(String grupo) {
    return RestClient.create()
        .get()
        .uri("http://localhost:" + port + "/v3/api-docs/" + grupo)
        .retrieve()
        .body(String.class);
  }

  /**
   * A superficie administrativa fica FORA do documento publicado. Emitir credencial de tenant e
   * ligar/desligar regra regulatoria nao sao capacidades que o parceiro precisa conhecer, e um
   * mapa delas e reconhecimento de graca para quem procurar.
   */
  @Test
  void oContratoDoParceiroNaoExpoeRotaAdministrativa() {
    String parceiro = spec("parceiro");

    assertThat(parceiro).contains("/v1/assessments");
    assertThat(parceiro).doesNotContain("/v1/risk-rules");
    assertThat(parceiro).doesNotContain("/v1/tenants");
    assertThat(parceiro).doesNotContain("/v1/webhook-endpoints");
  }

  @Test
  void oGrupoAdministrativoExisteSeparado() {
    assertThat(spec("admin")).contains("/v1/risk-rules");
  }
```

- [ ] **Step 2: Rodar e confirmar que falha**

```bash
JAVA_HOME="C:/Users/leona/.jdks/corretto-25.0.3" ./mvnw -pl services/risk-engine -am test -Dtest=OpenApiDocumentIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Esperado: FALHA nos dois casos novos — os grupos não existem (404).

- [ ] **Step 3: Criar a configuração dos grupos**

`services/risk-engine/src/main/java/com/barrier/riskengine/web/OpenApiConfig.java`:

```java
package com.barrier.riskengine.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dois contratos, e a separacao e de seguranca, nao de organizacao.
 *
 * <p>O spec default documenta TUDO — inclusive emitir credencial de tenant
 * ({@code /v1/tenants/**}) e ligar ou desligar regra regulatoria ({@code /v1/risk-rules}).
 * Publicar isso entrega o mapa da superficie administrativa a quem nunca deveria saber que ela
 * existe: mesmo raciocinio que tirou o {@code /actuator} da porta de negocio.
 *
 * <p>O grupo {@code parceiro} e o artefato publicavel. O grupo {@code admin} existe para uso
 * interno e <b>nunca</b> e publicado — nao esta protegido por estar escondido (o
 * {@code AdminApiKeyFilter} e quem protege), mas nao ha razao de dar o mapa de graca.
 *
 * <p>A lista de caminhos administrativos e escrita aqui e conferida contra
 * {@code ApiRoutes} pelo teste de cobertura: duas copias de uma mesma verdade divergem, e a
 * divergencia aqui e um endpoint administrativo publicado sem ninguem notar.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  public GroupedOpenApi contratoDoParceiro() {
    return GroupedOpenApi.builder()
        .group("parceiro")
        .pathsToMatch("/v1/**")
        .pathsToExclude("/v1/risk-rules/**", "/v1/tenants/**", "/v1/webhook-endpoints/**")
        .build();
  }

  @Bean
  public GroupedOpenApi contratoAdministrativo() {
    return GroupedOpenApi.builder()
        .group("admin")
        .pathsToMatch("/v1/risk-rules/**", "/v1/tenants/**", "/v1/webhook-endpoints/**")
        .build();
  }

  @Bean
  public OpenAPI metadados() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Barrier — motor de decisao de KYC/PLD-FT")
                .version("v1")
                .description(
                    "Avaliacao de risco com fatores explicaveis e trilha auditavel. "
                        + "Toda rota exige credencial de tenant; o desfecho chega por webhook "
                        + "assinado com HMAC."));
  }
}
```

⚠️ Se `org.springdoc.core.models.GroupedOpenApi` não existir na 3.0.0, localizar o pacote correto antes de prosseguir:

```bash
unzip -l ~/.m2/repository/org/springdoc/springdoc-openapi-starter-common/3.0.0/springdoc-openapi-starter-common-3.0.0.jar | grep -i GroupedOpenApi
```

- [ ] **Step 4: Rodar e confirmar que passa**

```bash
JAVA_HOME="C:/Users/leona/.jdks/corretto-25.0.3" ./mvnw -pl services/risk-engine -am test -Dtest=OpenApiDocumentIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Esperado: PASSA, 4 testes.

- [ ] **Step 5: Commit**

```bash
git add services/risk-engine/src
git commit -m "feat(api): superficie administrativa fora do contrato publicado"
```

---

### Task 3: O teste que impede rota nova de nascer sem documentação

Espelha o `ApiRouteCoverageTest`: o problema não é *um* endpoint sem doc, é a **categoria** — endpoint novo nasce indocumentado e nada avisa. Foi assim que `/v1/mesa` nasceu fora do filtro de autenticação.

**Files:**
- Create: `services/risk-engine/src/test/java/com/barrier/riskengine/web/OpenApiCoverageIntegrationTest.java`

**Interfaces:**
- Consumes: `GET /v3/api-docs/parceiro` da Task 2; `ApiRoutes.isAdmin(String)` e `ApiRoutes.isTenantScoped(String)`, já existentes.
- Produces: nada consumido por tarefas posteriores.

- [ ] **Step 1: Escrever o teste**

```java
package com.barrier.riskengine.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Nenhuma rota de negocio pode existir sem estar no contrato publicado.
 *
 * <p>Irmao do {@code ApiRouteCoverageTest}, e pelo mesmo motivo: o risco nao e um endpoint
 * esquecido, e a CATEGORIA — endpoint novo nasce indocumentado e nada no build aponta. Em A o
 * produto e a integracao, entao rota sem contrato e funcionalidade que o parceiro nao consegue
 * usar, mesmo estando pronta e testada.
 *
 * <p>Enumera os controllers pelo bytecode (nao por lista escrita a mao) e exige que cada base path
 * com escopo de tenant apareca no spec do grupo {@code parceiro}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OpenApiCoverageIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.barrier.riskengine");

  @LocalServerPort int port;

  /** Guard antivacuo: sem controllers importados, tudo abaixo passaria sobre lista vazia. */
  @Test
  void encontraOsControllers() {
    assertThat(basePathsDeTenant())
        .as("nenhum controller de tenant importado — o teste passaria vacuamente")
        .hasSizeGreaterThan(3);
  }

  @Test
  void todaRotaDeTenantEstaNoContratoDoParceiro() {
    String spec =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/v3/api-docs/parceiro")
            .retrieve()
            .body(String.class);

    for (String path : basePathsDeTenant()) {
      assertThat(spec)
          .as("rota %s existe e nao esta no contrato publicado — o parceiro nao tem como usar", path)
          .contains(path);
    }
  }

  /** Base paths dos controllers que NAO sao administrativos, lidos do bytecode. */
  private static List<String> basePathsDeTenant() {
    List<String> paths = new ArrayList<>();
    for (JavaClass tipo : CLASSES) {
      for (JavaAnnotation<?> anotacao : tipo.getAnnotations()) {
        if (!anotacao.getRawType().getName().endsWith("RequestMapping")) {
          continue;
        }
        Object valor = anotacao.getProperties().get("value");
        if (valor instanceof String[] valores && valores.length > 0) {
          String path = valores[0];
          if (!ApiRoutes.isAdmin(concreteProbe(path))) {
            paths.add(path);
          }
        }
      }
    }
    return paths;
  }

  /** Troca a variavel de caminho por um valor concreto, para o matcher de rota conseguir casar. */
  private static String concreteProbe(String path) {
    return path.replaceAll("\\{[^}]+\\}", "x");
  }
}
```

- [ ] **Step 2: Rodar e observar o resultado**

```bash
JAVA_HOME="C:/Users/leona/.jdks/corretto-25.0.3" ./mvnw -pl services/risk-engine -am test -Dtest=OpenApiCoverageIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Esperado: PASSA — o `pathsToMatch("/v1/**")` da Task 2 já cobre tudo. **Se falhar**, o path reportado revela uma rota fora de `/v1/**` ou excluída indevidamente; corrigir a exclusão em `OpenApiConfig`, nunca afrouxar o teste.

- [ ] **Step 3: Provar que o teste pega o bug (mutação)**

Trocar temporariamente, em `OpenApiConfig.contratoDoParceiro()`, `.pathsToMatch("/v1/**")` por `.pathsToMatch("/v1/assessments/**")` e rodar de novo.

Esperado: **FALHA**, citando `/v1/subjects` e as demais. Reverter em seguida.

Sem este passo o teste pode estar passando por acidente — o mesmo cuidado que o `ApiRouteCoverageTest` toma com o guard antivácuo.

- [ ] **Step 4: Commit**

```bash
git add services/risk-engine/src/test
git commit -m "test(api): rota de negocio sem contrato publicado quebra o build"
```

---

### Task 4: Contrato da webhook-api

A webhook-api tem API própria (`/v1/webhook-endpoints`) e ela é **inteiramente administrativa** — o parceiro não a chama, quem a chama é a operação para registrar destino e rotacionar segredo.

**Files:**
- Modify: `services/webhook-api/pom.xml`
- Create: `services/webhook-api/src/main/java/com/barrier/webhook/web/OpenApiConfig.java`
- Create: `services/webhook-api/src/main/resources/application-prod.yml` — **não existe hoje**; só a risk-engine tem um
- Test: `services/webhook-api/src/test/java/com/barrier/webhook/OpenApiDocumentIntegrationTest.java`

**Interfaces:**
- Consumes: a versão fixada no `pom.xml` raiz (Task 1).
- Produces: `GET /v3/api-docs` na webhook-api.

- [ ] **Step 1: Escrever o teste que falha**

```java
package com.barrier.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A webhook-api tambem publica contrato — e o dela e inteiramente administrativo.
 *
 * <p>Registrar destino e rotacionar segredo sao operacoes da operacao, nao do parceiro: por isso
 * nao ha grupo "parceiro" aqui. O que o parceiro precisa saber sobre webhook (como verificar o
 * HMAC, o que fazer com X-Barrier-Signature-Previous) e guia de integracao, nao referencia de API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OpenApiDocumentIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  @Test
  void serveOContrato() {
    String corpo =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/v3/api-docs")
            .retrieve()
            .body(String.class);

    assertThat(corpo).isNotBlank();
    assertThat(corpo).contains("/v1/webhook-endpoints");
  }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

```bash
JAVA_HOME="C:/Users/leona/.jdks/corretto-25.0.3" ./mvnw -pl services/webhook-api -am test -Dtest=OpenApiDocumentIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Esperado: FALHA com 404.

- [ ] **Step 3: Adicionar a dependência**

Em `services/webhook-api/pom.xml`, dentro de `<dependencies>`:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
</dependency>
```

- [ ] **Step 4: Adicionar os metadados**

`services/webhook-api/src/main/java/com/barrier/webhook/web/OpenApiConfig.java`:

```java
package com.barrier.webhook.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadados do contrato da Webhook API.
 *
 * <p>Sem grupos, ao contrario da risk-engine: <b>toda</b> rota deste servico e administrativa
 * (registrar destino, rotacionar segredo), entao nao ha subconjunto publicavel a separar. Se um
 * dia surgir endpoint que o parceiro chame — historico de entrega, reenvio —, os grupos entram
 * aqui no mesmo desenho.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI metadados() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Barrier — Webhook API (administrativa)")
                .version("v1")
                .description(
                    "Registro do destino de callback por tenant e rotacao do segredo HMAC. "
                        + "Protegida por X-Admin-Key; nao e superficie de parceiro."));
  }
}
```

- [ ] **Step 5: Rodar e confirmar que passa**

```bash
JAVA_HOME="C:/Users/leona/.jdks/corretto-25.0.3" ./mvnw -pl services/webhook-api -am test -Dtest=OpenApiDocumentIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Esperado: PASSA.

- [ ] **Step 6: Desligar a UI em prod**

⚠️ Este arquivo **não existe** na webhook-api — só a risk-engine tem `application-prod.yml`. Criar
`services/webhook-api/src/main/resources/application-prod.yml` com o conteúdo abaixo, e **conferir
antes** se o profile `prod` deste serviço não dependia da ausência dele (hoje não depende: os guards
de prod da webhook-api leem `Environment.getActiveProfiles()`, não o arquivo).

```yaml
springdoc:
  # Mesmo raciocinio da risk-engine: spec sim, UI viva no host da API nao.
  swagger-ui:
    enabled: false
```

- [ ] **Step 7: Suíte completa dos dois serviços**

```bash
JAVA_HOME="C:/Users/leona/.jdks/corretto-25.0.3" ./mvnw test
```

Esperado: BUILD SUCCESS. Baseline antes desta fatia: **753 testes** (655 risk-engine + 66 webhook-api + 32 commons).

- [ ] **Step 8: Commit**

```bash
git add services/webhook-api pom.xml
git commit -m "feat(api): contrato OpenAPI da webhook-api"
```

---

### Task 5: Publicar o spec como artefato do CI

Spec que só existe em runtime não é produto: o parceiro precisa do arquivo antes de ter acesso ao ambiente.

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: o helper privado `String spec(String grupo)` definido na Task 2, Step 1, dentro de `OpenApiDocumentIntegrationTest` — o teste desta tarefa vive na mesma classe e o reutiliza; e `GET /v3/api-docs` da webhook-api (Task 4).
- Produces: artefato `openapi-parceiro.json` em cada execução do CI.

- [ ] **Step 1: Ler o workflow atual**

```bash
cat .github/workflows/ci.yml
```

Identificar o job que roda `mvnw verify` — o passo novo entra depois dele.

- [ ] **Step 2: Escrever o teste que gera o arquivo**

Acrescentar a `OpenApiDocumentIntegrationTest` da risk-engine:

```java
  /**
   * Grava o spec em target/, de onde o CI o publica como artefato.
   *
   * <p>Gerado por TESTE e nao por plugin de build: assim o arquivo publicado e exatamente o que a
   * aplicacao serve, e nao o que uma segunda ferramenta acha que ela serve. Duas fontes divergem, e
   * a divergencia aqui e o parceiro integrando contra um contrato que nao existe.
   */
  @Test
  void gravaOContratoParaPublicacao() throws Exception {
    String parceiro = spec("parceiro");
    java.nio.file.Path destino = java.nio.file.Path.of("target", "openapi-parceiro.json");
    java.nio.file.Files.writeString(destino, parceiro);

    assertThat(destino).exists();
    assertThat(java.nio.file.Files.readString(destino)).contains("/v1/assessments");
  }
```

- [ ] **Step 3: Rodar e confirmar que passa e que o arquivo existe**

```bash
JAVA_HOME="C:/Users/leona/.jdks/corretto-25.0.3" ./mvnw -pl services/risk-engine -am test -Dtest=OpenApiDocumentIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
ls -la services/risk-engine/target/openapi-parceiro.json
```

Esperado: PASSA e o arquivo existe.

- [ ] **Step 4: Publicar como artefato no CI**

Em `.github/workflows/ci.yml`, depois do passo de build/verify:

```yaml
      - name: Publicar contrato OpenAPI
        # Spec que so existe em runtime nao serve para integrar: o parceiro precisa do arquivo
        # antes de ter acesso ao ambiente.
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: openapi
          path: services/*/target/openapi-*.json
          if-no-files-found: error
```

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml services/risk-engine/src/test
git commit -m "ci(api): publica o contrato OpenAPI como artefato"
```

---

## Depois desta fatia

O item "OpenAPI gerado, versionado e publicado" da Fase 1 fecha. **Não** fecha o item "guia de integração público" — spec é referência, não é onboarding, e o critério de pronto de lá exige um dev externo integrando **observado**, não presumido.

Próximos itens da Fase 1, em ordem: guia de integração · sandbox exposto como produto · listagem paginada · `t=` no HMAC (antes de haver parceiro integrado, porque depois é quebra de contrato).
