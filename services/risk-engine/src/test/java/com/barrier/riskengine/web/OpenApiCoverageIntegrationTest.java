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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Nenhuma rota de negocio pode existir sem estar no contrato publicado.
 *
 * <p>Irmao do {@code ApiRouteCoverageTest}, e pelo mesmo motivo: o risco nao e um endpoint
 * esquecido, e a CATEGORIA — endpoint novo nasce indocumentado e nada no build aponta. Em
 * posicionamento API-first o produto e a integracao, entao rota sem contrato e funcionalidade que o
 * parceiro nao consegue usar, mesmo estando pronta e testada.
 *
 * <p>Enumera os controllers pelo bytecode (nao por lista escrita a mao) e exige que cada base path
 * com escopo de tenant apareca no spec do grupo {@code parceiro}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class OpenApiCoverageIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

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
