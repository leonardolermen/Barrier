package com.barrier.riskengine.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.assessment.controller.dto.AssessmentResponse;
import com.barrier.riskengine.assessment.controller.dto.SubmitAssessmentRequest;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentProcessor;
import com.barrier.riskengine.replay.controller.dto.ReplayResponse;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicy;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import com.barrier.riskengine.riskpolicy.service.RiskPolicyService;
import com.barrier.riskengine.tenant.service.ApiKeyService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * Contrato HTTP do replay, sobre uma decisão de verdade produzida pelo pipeline.
 *
 * <p>O que este teste existe para provar, e que um teste de domínio não alcança: <b>replayar não
 * escreve na trilha</b> (a contagem de {@code risk_scores} não muda), o escopo de tenant vale como
 * no resto de {@code /v1/assessments}, e avaliação sem decisão do motor responde 409 em vez de
 * estourar.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000"
    })
@Testcontainers
class DecisionReplayIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  @Autowired AssessmentProcessor processor;
  @Autowired ApiKeyService apiKeyService;
  @Autowired JdbcTemplate jdbc;
  @Autowired RiskPolicyService riskPolicyService;

  private RestClient clientDo(String tenantId) {
    String chave = apiKeyService.issue(tenantId, "replay-it").presentedValue();
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader("Authorization", "Bearer " + chave)
        .build();
  }

  private void seedTenant(String tenantId) {
    jdbc.update(
        "INSERT INTO tenants (id, name, active) VALUES (?, ?, true) ON CONFLICT (id) DO NOTHING",
        tenantId,
        tenantId);
  }

  /**
   * Cria e ativa uma versão de política com uma única regra que só pontua sobre {@code
   * identity.status} -- o mínimo que produz um fator {@code CUSTOM_} de verdade em {@code
   * evaluated_json} sem depender de bureau de PJ nenhum.
   */
  private int ativaPoliticaComFatorCustom(String tenantId) {
    Condition identidadeVerificada =
        new Condition.Comparison(
            FieldCatalog.V1.find("identity.status").orElseThrow(),
            Operator.EQ,
            Literal.text("VERIFIED"));
    PolicyRule regra =
        new PolicyRule(
            "CUSTOM_IDENTIDADE_OK", "identidade verificada", identidadeVerificada, 10, Severity.LOW, null);
    RiskPolicy criada =
        riskPolicyService.createDraft(
            tenantId, PolicyDomain.ONBOARDING, List.of(regra), "teste-replay@barrier");
    riskPolicyService.activate(tenantId, criada.version(), "teste-replay@barrier");
    return criada.version();
  }

  private String submete(RestClient client, String documento, String nome) {
    return client
        .post()
        .uri("/v1/assessments")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SubmitAssessmentRequest(DocumentType.CPF, documento, nome))
        .retrieve()
        .toEntity(AssessmentResponse.class)
        .getBody()
        .id();
  }

  private ReplayResponse replay(RestClient client, String id, String modo) {
    return client
        .post()
        .uri("/v1/assessments/" + id + "/replay?mode=" + modo)
        .retrieve()
        .toEntity(ReplayResponse.class)
        .getBody();
  }

  /**
   * Processa e confirma que <b>esta</b> avaliação concluiu.
   *
   * <p>Não se afirma o tamanho do lote: os testes desta classe compartilham banco, e um deles
   * deixa avaliação pendente de propósito — o lote seguinte a recolhe junto. Assertar "processou 1"
   * acoplaria o teste à ordem de execução dos irmãos.
   */
  private void processaAteConcluir(RestClient client, String id) {
    processor.process();
    String status =
        client
            .get()
            .uri("/v1/assessments/" + id)
            .retrieve()
            .toEntity(AssessmentResponse.class)
            .getBody()
            .status();
    assertThat(status).isNotEqualTo("EM_ANALISE");
  }

  private int scoresDe(String assessmentId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM risk_scores WHERE assessment_id = ?", Integer.class, assessmentId);
  }

  @Test
  void as_decided_devolve_o_dossie_e_nao_escreve_na_trilha() {
    RestClient client = clientDo("default");
    String id = submete(client, "111.444.777-35", "Cliente Comum");
    processaAteConcluir(client, id);

    int antes = scoresDe(id);
    ReplayResponse resposta = replay(client, id, "AS_DECIDED");

    assertThat(resposta).isNotNull();
    assertThat(resposta.assessmentId()).isEqualTo(id);
    assertThat(resposta.mode()).isEqualTo("AS_DECIDED");
    assertThat(resposta.arithmetic().consistent())
        .as("a decisão que o próprio motor acabou de gravar tem de fechar consigo mesma")
        .isTrue();
    assertThat(resposta.replayed()).as("AS_DECIDED não reexecuta nada").isNull();
    assertThat(resposta.recorded().engineVersion()).isNotBlank();
    // A trilha completa da V028 grava TODAS as regras, não só as que dispararam.
    assertThat(resposta.rules()).hasSizeGreaterThan(5);
    assertThat(resposta.rules()).allMatch(r -> "NOT_COMPARED".equals(r.comparison()));
    assertThat(scoresDe(id)).as("replayar não pode criar linha nova de decisão").isEqualTo(antes);
  }

  @Test
  void current_engine_reexecuta_sem_gravar_e_sem_mudar_o_desfecho() {
    RestClient client = clientDo("default");
    String id = submete(client, "111.444.777-35", "Outro Cliente Comum");
    processaAteConcluir(client, id);

    int antes = scoresDe(id);
    ReplayResponse resposta = replay(client, id, "CURRENT_ENGINE");

    assertThat(resposta.replayed()).isNotNull();
    // Mesmo motor, mesma evidência, cadastro intocado desde a decisão: nada pode ter mudado.
    assertThat(resposta.verdict()).isEqualTo("SAME_DECISION");
    // Zero lacunas, inclusive de autoria: nenhuma política foi alterada nesta base, então toda
    // regra tem estado apurável (semeada e intocada, ou nunca registrada e ativa por fail-open).
    assertThat(resposta.gaps()).isEmpty();
    assertThat(resposta.replayed().score()).isEqualTo(resposta.recorded().score());
    assertThat(resposta.rules()).allMatch(r -> "SAME".equals(r.comparison()));
    assertThat(scoresDe(id)).isEqualTo(antes);
  }

  @Test
  void avaliacao_de_outro_tenant_responde_404_e_nao_403() {
    // 403 confirmaria que o id existe, transformando o endpoint em oráculo.
    jdbc.update(
        "INSERT INTO tenants (id, name, active) VALUES (?, ?, true) ON CONFLICT (id) DO NOTHING",
        "outro-parceiro",
        "Parceiro vizinho");
    RestClient dono = clientDo("default");
    String id = submete(dono, "111.444.777-35", "Cliente do Default");
    processaAteConcluir(dono, id);

    RestClient intruso = clientDo("outro-parceiro");
    assertThatThrownBy(() -> replay(intruso, id, "AS_DECIDED"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void avaliacao_ainda_sem_decisao_responde_409() {
    RestClient client = clientDo("default");
    String id = submete(client, "111.444.777-35", "Ainda Em Analise");
    // sem processor.process(): não há linha em risk_scores

    assertThatThrownBy(() -> replay(client, id, "AS_DECIDED"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void avaliacao_inexistente_responde_404() {
    RestClient client = clientDo("default");

    assertThatThrownBy(() -> replay(client, UUID.randomUUID().toString(), "AS_DECIDED"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void as_decided_permanece_integro_com_fator_custom() {
    String tenantId = "politica-parceiro-as-decided";
    seedTenant(tenantId);
    int versaoAtivada = ativaPoliticaComFatorCustom(tenantId);

    RestClient client = clientDo(tenantId);
    String id = submete(client, "111.444.777-35", "Cliente Com Politica");
    processaAteConcluir(client, id);

    ReplayResponse resposta = replay(client, id, "AS_DECIDED");

    assertThat(resposta.arithmetic().consistent())
        .as(
            "o fator custom soma no mesmo evaluated_json e passa pela mesma agregação -- a "
                + "reconferência tem de fechar como para qualquer regra de código")
        .isTrue();
    assertThat(resposta.recorded().policyVersion()).isEqualTo(versaoAtivada);
    assertThat(resposta.rules())
        .filteredOn(r -> r.ruleCode().equals("CUSTOM_IDENTIDADE_OK"))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.recordedOutcome()).isEqualTo("TRIGGERED");
              // AS_DECIDED não reexecuta nada, custom ou de código.
              assertThat(r.comparison()).isEqualTo("NOT_COMPARED");
            });
  }

  @Test
  void dossie_reporta_a_versao_da_politica_que_decidiu() {
    // O cenário "parceiro editou a política entre a decisão e o replay" (os dois eixos
    // divergindo) já está provado com precisão em DecisionReplayServiceTest, com os dois lados
    // sob controle do teste. Aqui a única coisa que falta provar é que o campo atravessa o
    // HTTP/JSON de ponta a ponta -- por isso uma única versão, estável, é suficiente.
    String tenantId = "politica-parceiro-versoes";
    seedTenant(tenantId);
    int versaoAtivada = ativaPoliticaComFatorCustom(tenantId);

    RestClient client = clientDo(tenantId);
    String id = submete(client, "111.444.777-35", "Cliente Com Politica Estavel");
    processaAteConcluir(client, id);

    ReplayResponse resposta = replay(client, id, "CURRENT_ENGINE");

    assertThat(resposta.verdict()).isEqualTo("SAME_DECISION");
    assertThat(resposta.recorded().policyVersion()).isEqualTo(versaoAtivada);
    assertThat(resposta.replayed().policyVersion()).isEqualTo(versaoAtivada);
  }

  @Test
  void replay_sem_credencial_responde_401() {
    RestClient anonimo = RestClient.builder().baseUrl("http://localhost:" + port).build();

    assertThatThrownBy(
            () ->
                anonimo
                    .post()
                    .uri("/v1/assessments/" + UUID.randomUUID() + "/replay")
                    .retrieve()
                    .toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(
            e ->
                assertThat(((HttpClientErrorException) e).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }
}
