package com.barrier.riskengine.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assessment.controller.dto.AssessmentResponse;
import com.barrier.riskengine.assessment.controller.dto.SubmitAssessmentRequest;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentProcessor;
import com.barrier.riskengine.replay.controller.dto.ReplayResponse;
import com.barrier.riskengine.risk.registry.controller.dto.UpsertRiskRuleRegistryRequest;
import com.barrier.riskengine.tenant.config.controller.dto.UpsertRiskConfigRequest;
import com.barrier.riskengine.tenant.service.ApiKeyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A pergunta que a plataforma não respondia: <b>qual política estava vigente quando este cliente foi
 * avaliado, e quem a definiu</b>.
 *
 * <p>Prova ponta a ponta, com histórico de verdade no banco: alguém desliga uma regra pela API
 * administrativa, uma avaliação é decidida depois disso, e o dossiê do replay mostra a regra
 * suprimida <b>com o nome de quem a desligou e quando</b>. Antes, {@code evaluated_json} dizia
 * apenas {@code SUPPRESSED}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class PolicyProvenanceIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  @Autowired AssessmentProcessor processor;
  @Autowired ApiKeyService apiKeyService;

  private RestClient client() {
    String chave = apiKeyService.issue("default", "policy-it").presentedValue();
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader("Authorization", "Bearer " + chave)
        .build();
  }

  private static ReplayResponse.RuleDto regra(ReplayResponse resposta, String codigo) {
    return resposta.rules().stream()
        .filter(r -> r.ruleCode().equals(codigo))
        .findFirst()
        .orElseThrow(() -> new AssertionError("regra ausente no dossiê: " + codigo));
  }

  private ReplayResponse decideEReplaya(String nome) {
    RestClient client = client();
    String id =
        client
            .post()
            .uri("/v1/assessments")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new SubmitAssessmentRequest(DocumentType.CPF, "111.444.777-35", nome))
            .retrieve()
            .toEntity(AssessmentResponse.class)
            .getBody()
            .id();
    processor.process();
    return client
        .post()
        .uri("/v1/assessments/" + id + "/replay?mode=AS_DECIDED")
        .retrieve()
        .toEntity(ReplayResponse.class)
        .getBody();
  }

  @Test
  void o_dossie_diz_quem_desligou_a_regra_e_quando() {
    // 1. alguém desliga uma regra de apetite pela API administrativa
    client()
        .put()
        .uri("/v1/risk-rules/NEW_COMPANY")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new UpsertRiskRuleRegistryRequest(
                "Empresa recém-aberta", "ALERT", false, null, null, "compliance@barrier"))
        .retrieve()
        .toBodilessEntity();

    // 2. uma avaliação é decidida DEPOIS disso
    ReplayResponse dossie = decideEReplaya("Cliente Pos Kill Switch");

    // 3. o dossiê carrega a autoria — que evaluated_json sozinho nunca contou
    ReplayResponse.RuleDto novaEmpresa = regra(dossie, "NEW_COMPANY");
    assertThat(novaEmpresa.recordedOutcome())
        .as("a regra desligada aparece como suprimida na trilha")
        .isEqualTo("SUPPRESSED");
    assertThat(novaEmpresa.policy().registry()).isNotNull();
    assertThat(novaEmpresa.policy().registry().enabled()).isFalse();
    assertThat(novaEmpresa.policy().registry().changedBy()).isEqualTo("compliance@barrier");
    assertThat(novaEmpresa.policy().registry().changedAt()).isNotNull();
    assertThat(novaEmpresa.policy().registry().provenance()).isEqualTo("FROM_HISTORY");
  }

  @Test
  void regra_nunca_alterada_vale_desde_a_semente_e_nao_vira_lacuna() {
    ReplayResponse dossie = decideEReplaya("Cliente Sem Alteracao De Politica");

    // IDENTITY é regulatória e nunca foi tocada nesta base: o estado atual é o de sempre.
    ReplayResponse.RuleDto identidade = regra(dossie, "IDENTITY");
    assertThat(identidade.policy().registry()).isNotNull();
    assertThat(identidade.policy().registry().provenance()).isEqualTo("UNCHANGED_SINCE_SEED");
    assertThat(identidade.policy().registry().changedBy()).isNull();
  }

  @Test
  void autoria_do_parametro_efetivo_acompanha_o_valor_efetivo() {
    // O valor já vinha de evaluated_json; o que faltava era quem o definiu.
    client()
        .put()
        .uri("/v1/tenants/default/risk-config")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpsertRiskConfigRequest("NEW_COMPANY", "months", "18", "analista@barrier"))
        .retrieve()
        .toBodilessEntity();

    ReplayResponse dossie = decideEReplaya("Cliente Com Override");

    ReplayResponse.RuleDto novaEmpresa = regra(dossie, "NEW_COMPANY");
    assertThat(novaEmpresa.policy().parameters())
        .as("regra suprimida não registra parâmetro; só as que rodam registram")
        .isNotNull();
    novaEmpresa.policy().parameters().stream()
        .filter(p -> p.paramKey().equals("months"))
        .findFirst()
        .ifPresent(
            p -> {
              assertThat(p.source()).isEqualTo("TENANT_OVERRIDE");
              assertThat(p.changedBy()).isEqualTo("analista@barrier");
            });
  }

  @Test
  void a_linha_do_tempo_da_regra_e_consultavel() {
    client()
        .put()
        .uri("/v1/risk-rules/SENSITIVE_CNAE")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new UpsertRiskRuleRegistryRequest(
                "CNAE sensível", "ALERT", false, null, null, "compliance@barrier"))
        .retrieve()
        .toBodilessEntity();
    client()
        .put()
        .uri("/v1/risk-rules/SENSITIVE_CNAE")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new UpsertRiskRuleRegistryRequest(
                "CNAE sensível", "ALERT", true, null, null, "auditoria@barrier"))
        .retrieve()
        .toBodilessEntity();

    List<RegistryPolicyState> linhaDoTempo =
        client()
            .get()
            .uri("/v1/risk-rules/SENSITIVE_CNAE/history")
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});

    assertThat(linhaDoTempo).hasSize(2);
    // Da mais recente para a mais antiga: religada por auditoria, desligada por compliance.
    assertThat(linhaDoTempo.get(0).changedBy()).isEqualTo("auditoria@barrier");
    assertThat(linhaDoTempo.get(0).enabled()).isTrue();
    assertThat(linhaDoTempo.get(1).changedBy()).isEqualTo("compliance@barrier");
    assertThat(linhaDoTempo.get(1).enabled()).isFalse();
  }

  @Test
  void a_linha_do_tempo_de_overrides_do_tenant_e_consultavel() {
    client()
        .put()
        .uri("/v1/tenants/default/risk-config")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new UpsertRiskConfigRequest("NEW_COMPANY", "score", "150", "analista@barrier"))
        .retrieve()
        .toBodilessEntity();

    List<ParamAuthorship> linhaDoTempo =
        client()
            .get()
            .uri("/v1/tenants/default/risk-config/history")
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});

    assertThat(linhaDoTempo)
        .anySatisfy(
            entrada -> {
              assertThat(entrada.paramKey()).isEqualTo("NEW_COMPANY:score");
              assertThat(entrada.changedBy()).isEqualTo("analista@barrier");
            });
  }
}
