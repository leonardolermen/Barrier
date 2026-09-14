package com.barrier.riskengine.riskpolicy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.assessment.controller.dto.AssessmentResponse;
import com.barrier.riskengine.assessment.controller.dto.SubmitAssessmentRequest;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentProcessor;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.riskpolicy.controller.dto.ActivatePolicyRequest;
import com.barrier.riskengine.riskpolicy.controller.dto.CreatePolicyRequest;
import com.barrier.riskengine.riskpolicy.controller.dto.PolicyConditionDto;
import com.barrier.riskengine.riskpolicy.controller.dto.PolicyFieldCatalogResponse;
import com.barrier.riskengine.riskpolicy.controller.dto.PolicyLiteralDto;
import com.barrier.riskengine.riskpolicy.controller.dto.PolicyResponse;
import com.barrier.riskengine.riskpolicy.controller.dto.PolicyRuleDto;
import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import com.barrier.riskengine.tenant.service.ApiKeyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Fim-a-fim de {@code /v1/policies} e {@code /v1/policy-fields}: o parceiro escreve uma regra,
 * ativa, e ela passa a valer numa avaliação de verdade -- e o isolamento entre tenants vale para
 * política como já vale para avaliação (ver {@code TenantIsolationIntegrationTest}).
 *
 * <p>A regra de exemplo usa {@code identity.status EQ VERIFIED} em vez de {@code
 * company.openingDate WITHIN_LAST} sobre uma PJ recém-aberta (o exemplo do brief da task,
 * {@code CUSTOM_EMPRESA_NOVA}): não há bureau de CNPJ simulado neste repositório (só
 * {@code FakeCpfBureauProvider} para CPF), e os providers reais de PJ (BrasilAPI, BigBoost)
 * exigem rede -- este teste roda só contra Testcontainers. O CPF {@code 111.444.777-35} com o
 * bureau simulado é o mesmo par determinístico já usado em {@code
 * AssessmentFlowIntegrationTest}, e prova exatamente a mesma coisa: política ativa -> regra
 * custom entra no motor -> fator aparece na avaliação de verdade.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      "barrier.verification.required=false"
    })
@Testcontainers
class RiskPolicyControllerIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  @Autowired ApiKeyService apiKeyService;
  @Autowired AssessmentProcessor processor;
  @Autowired JdbcTemplate jdbc;

  private String credencialDe(String tenantId) {
    jdbc.update(
        "INSERT INTO tenants (id, name, active) VALUES (?, ?, true) ON CONFLICT (id) DO NOTHING",
        tenantId,
        tenantId);
    return apiKeyService.issue(tenantId, "integracao").presentedValue();
  }

  private RestClient com(String apiKey) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .build();
  }

  private PolicyRuleDto regraIdentidadeVerificada(String codigo, int score) {
    PolicyConditionDto when =
        new PolicyConditionDto.Comparison(
            "identity.status", Operator.EQ, new PolicyLiteralDto.Text("VERIFIED"));
    return new PolicyRuleDto(codigo, "identidade verificada", when, score, Severity.MEDIUM, null);
  }

  private PolicyResponse criaRascunho(RestClient client, PolicyRuleDto... regras) {
    return client
        .post()
        .uri("/v1/policies")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new CreatePolicyRequest(PolicyDomain.ONBOARDING, List.of(regras), "ana@parceiro"))
        .retrieve()
        .toEntity(PolicyResponse.class)
        .getBody();
  }

  @Test
  void cria_rascunho_ativa_e_a_regra_passa_a_pontuar() {
    String tenant = credencialDe("parceiro-policy-a");
    RestClient client = com(tenant);

    var created =
        criaRascunho(client, regraIdentidadeVerificada("CUSTOM_IDENTIDADE_VERIFICADA", 50));
    assertThat(created.status()).isEqualTo("DRAFT");
    assertThat(created.version()).isEqualTo(1);

    PolicyResponse ativada =
        client
            .post()
            .uri("/v1/policies/{v}/activate", created.version())
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ActivatePolicyRequest("supervisor@parceiro"))
            .retrieve()
            .toEntity(PolicyResponse.class)
            .getBody();
    assertThat(ativada.status()).isEqualTo("ACTIVE");

    var submissao =
        client
            .post()
            .uri("/v1/assessments")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new SubmitAssessmentRequest(DocumentType.CPF, "111.444.777-35", "Fulano de Tal"))
            .retrieve()
            .toEntity(AssessmentResponse.class)
            .getBody();

    assertThat(processor.process()).isEqualTo(1);

    AssessmentResponse concluida =
        client
            .get()
            .uri("/v1/assessments/{id}", submissao.id())
            .retrieve()
            .toEntity(AssessmentResponse.class)
            .getBody();

    assertThat(concluida.factors())
        .withFailMessage("fatores: %s", concluida.factors())
        .anyMatch(f -> f.startsWith("CUSTOM_IDENTIDADE_VERIFICADA"));
  }

  @Test
  void politica_invalida_responde_400_citando_campo_e_operador() {
    String tenant = credencialDe("parceiro-policy-b");
    RestClient client = com(tenant);
    PolicyRuleDto regraComScoreNegativo = regraIdentidadeVerificada("CUSTOM_AFROUXA", -50);

    assertThatThrownBy(
            () ->
                client
                    .post()
                    .uri("/v1/policies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        new CreatePolicyRequest(
                            PolicyDomain.ONBOARDING,
                            List.of(regraComScoreNegativo),
                            "ana@parceiro"))
                    .retrieve()
                    .toEntity(PolicyResponse.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e -> {
              HttpClientErrorException ex = (HttpClientErrorException) e;
              assertThat(ex.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
              assertThat(ex.getResponseBodyAsString())
                  .contains("score")
                  .contains("CUSTOM_AFROUXA");
            });
  }

  @Test
  void nao_enxerga_politica_de_outro_tenant() {
    String tenantA = credencialDe("parceiro-policy-c");
    String tenantB = credencialDe("parceiro-policy-d");

    var criada = criaRascunho(com(tenantA), regraIdentidadeVerificada("CUSTOM_A", 10));

    assertThatThrownBy(
            () ->
                com(tenantB)
                    .get()
                    .uri("/v1/policies/{v}", criada.version())
                    .retrieve()
                    .toEntity(PolicyResponse.class))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode().value())
                    .isEqualTo(HttpStatus.NOT_FOUND.value()));

    // controle: A, dono da versão, continua enxergando -- prova que o 404 acima é isolamento,
    // não um endpoint quebrado.
    PolicyResponse deA =
        com(tenantA)
            .get()
            .uri("/v1/policies/{v}", criada.version())
            .retrieve()
            .toEntity(PolicyResponse.class)
            .getBody();
    assertThat(deA.version()).isEqualTo(criada.version());
  }

  /**
   * Fecha o achado da revisão: o serviço já tinha {@code list}, mas nenhum teste chamava a rota
   * pela superfície HTTP real -- e o ponto desta task é provar a superfície, não o serviço em
   * processo.
   */
  @Test
  void lista_as_versoes_do_tenant_por_http() {
    String tenant = credencialDe("parceiro-policy-f");
    RestClient client = com(tenant);

    var v1 = criaRascunho(client, regraIdentidadeVerificada("CUSTOM_LISTA_A", 10));
    var v2 = criaRascunho(client, regraIdentidadeVerificada("CUSTOM_LISTA_B", 20));

    List<PolicyResponse> listadas =
        client
            .get()
            .uri("/v1/policies")
            .retrieve()
            .body(new ParameterizedTypeReference<List<PolicyResponse>>() {});

    assertThat(listadas)
        .withFailMessage("versões listadas: %s", listadas)
        .extracting(PolicyResponse::version)
        .contains(v1.version(), v2.version());
  }

  @Test
  void catalogo_de_campos_e_publicado_com_versao() {
    String tenant = credencialDe("parceiro-policy-e");

    PolicyFieldCatalogResponse catalogo =
        com(tenant)
            .get()
            .uri("/v1/policy-fields")
            .retrieve()
            .toEntity(PolicyFieldCatalogResponse.class)
            .getBody();

    assertThat(catalogo.version()).isGreaterThan(0);
    assertThat(catalogo.fields()).isNotEmpty();
    assertThat(catalogo.fields()).anyMatch(f -> f.id().equals("identity.status"));
  }
}
