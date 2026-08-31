package com.barrier.riskengine.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * O contrato público existe e é servido.
 *
 * <p>Em A o produto <b>é</b> a integração: um spec que não sobe não é documentação atrasada, é
 * produto quebrado. Por isso a verificação é de integração e não unitária — o que importa não é a
 * dependência estar no pom, é o documento sair pela porta que o parceiro alcança.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      // o contrato nao depende de pipeline rodando; adiar os jobs deixa o teste sobre o que ele mede
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class OpenApiDocumentIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

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

  private String spec(String grupo) {
    return RestClient.create()
        .get()
        .uri("http://localhost:" + port + "/v3/api-docs/" + grupo)
        .retrieve()
        .body(String.class);
  }

  /**
   * A superficie administrativa fica FORA do documento publicado. Emitir credencial de tenant e
   * ligar/desligar regra regulatoria nao sao capacidades que o parceiro precisa conhecer, e um mapa
   * delas e reconhecimento de graca para quem procurar.
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

  /**
   * O contrato nao pode MENTIR sobre como se chama a API.
   *
   * <p>{@code AuthenticatedTenant} e injetado por {@code TenantArgumentResolver} a partir da
   * credencial — o parceiro nunca o envia. Sem instruir o springdoc a ignora-lo, ele o introspecta
   * como argumento de controller e publica {@code tenant} como query parameter <b>obrigatorio</b>,
   * junto do formato interno de {@code Tenant} e do nome da chave. Um contrato que descreve um
   * parametro inexistente e pior que contrato nenhum: o dev externo tenta manda-lo, nao funciona, e
   * o unico caminho de volta e falar com o time — exatamente o que este trabalho existe para
   * eliminar.
   */
  @Test
  void oContratoNaoPedeParametroQueOParceiroNaoEnvia() {
    String parceiro = spec("parceiro");

    assertThat(parceiro)
        .as("tenant e resolvido da credencial, nao e parametro de requisicao")
        .doesNotContain("\"name\":\"tenant\"");
    assertThat(parceiro)
        .as("formato interno do tenant vazando nos schemas do contrato publico")
        .doesNotContain("AuthenticatedTenant");
  }

  /**
   * Como autenticar faz parte do contrato.
   *
   * <p>Toda rota de negocio exige {@code Authorization: Bearer brr_...}; um spec que documenta os
   * campos mas nao o esquema de autenticacao deixa o dev externo na primeira parede, com 401 e sem
   * explicacao.
   */
  @Test
  void oContratoDeclaraComoAutenticar() {
    String parceiro = spec("parceiro");

    assertThat(parceiro).contains("securitySchemes");
    assertThat(parceiro).contains("bearerAuth");
  }

  /**
   * Grava o spec em {@code target/}, de onde o CI o publica como artefato.
   *
   * <p>Gerado por TESTE e nao por plugin de build: assim o arquivo publicado e exatamente o que a
   * aplicacao serve, e nao o que uma segunda ferramenta acha que ela serve. Duas fontes divergem, e
   * a divergencia aqui e o parceiro integrando contra um contrato que nao existe.
   */
  @Test
  void gravaOContratoParaPublicacao() throws Exception {
    String parceiro = spec("parceiro");
    Path destino = Path.of("target", "openapi-parceiro.json");
    Files.writeString(destino, parceiro);

    assertThat(destino).exists();
    assertThat(Files.readString(destino)).contains("/v1/assessments");
  }
}
