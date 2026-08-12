package com.barrier.riskengine.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.assessment.controller.dto.AssessmentResponse;
import com.barrier.riskengine.assessment.controller.dto.SubmitAssessmentRequest;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.subject.profile.controller.dto.ProfileResponse;
import com.barrier.riskengine.subject.profile.controller.dto.UpdateProfileRequest;
import com.barrier.riskengine.tenant.repository.TenantRepository;
import com.barrier.riskengine.tenant.service.ApiKeyService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
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
 * Isolamento entre tenants sob autenticação por credencial.
 *
 * <p>Estes são os testes que fecham o achado: antes, {@code X-Client-Id} era um header
 * autodeclarado, então bastava conhecer o id de outro cliente para ler — e <b>aprovar</b> — as
 * avaliações dele. O critério de pronto do item não é "existe autenticação", é "forjar o header
 * não serve para nada".
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "barrier.assessment.processor-delay-ms=3600000",
      "barrier.outbox.relay-delay-ms=3600000",
      // com a chave de admin configurada, o gate deixa de ser inerte e pode ser exercitado
      "barrier.admin.api-key=chave-de-admin-de-teste-com-tamanho-ok"
    })
@Testcontainers
class TenantIsolationIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

  @Value("${local.server.port}")
  int port;

  @Autowired ApiKeyService apiKeyService;
  @Autowired TenantRepository tenantRepository;
  @Autowired JdbcTemplate jdbc;

  private String credencial;

  @BeforeEach
  void emiteCredencial() {
    credencial = apiKeyService.issue("default", "integracao").presentedValue();
  }

  private RestClient semCredencial() {
    return RestClient.builder().baseUrl("http://localhost:" + port).build();
  }

  private RestClient com(String apiKey) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .build();
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

  private HttpStatus statusDoErro(Throwable e) {
    return HttpStatus.valueOf(((HttpClientErrorException) e).getStatusCode().value());
  }

  @Test
  void semCredencialRecusa() {
    assertThatThrownBy(() -> submete(semCredencial(), "111.444.777-35", "Fulano"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(e -> assertThat(statusDoErro(e)).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  /** O header que antes bastava para se passar por qualquer cliente agora não é sequer lido. */
  @Test
  void headerXClientIdForjadoNaoAutentica() {
    RestClient forjado =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultHeader("X-Client-Id", "default")
            .build();

    assertThatThrownBy(() -> submete(forjado, "111.444.777-35", "Fulano"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(e -> assertThat(statusDoErro(e)).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void credencialMalformadaOuInexistenteRecusa() {
    assertThatThrownBy(() -> submete(com("nao-e-uma-chave"), "111.444.777-35", "Fulano"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(e -> assertThat(statusDoErro(e)).isEqualTo(HttpStatus.UNAUTHORIZED));

    assertThatThrownBy(() -> submete(com("brr_deadbeef_segredoinventado"), "529.982.247-25", "F"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(e -> assertThat(statusDoErro(e)).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void credencialValidaPassa() {
    String id = submete(com(credencial), "111.444.777-35", "Fulano de Tal");

    assertThat(id).isNotBlank();
  }

  /**
   * O segredo não é recuperável: mesmo conhecendo o {@code keyId} (que é público e aparece na
   * emissão), não dá para autenticar.
   */
  @Test
  void keyIdSemOSegredoNaoAutentica() {
    String keyId = credencial.split("_", 3)[1];

    assertThatThrownBy(() -> submete(com("brr_" + keyId + "_qualquercoisa"), "529.982.247-25", "F"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(e -> assertThat(statusDoErro(e)).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  /**
   * As duas autenticações respondem perguntas diferentes — "é qual cliente?" e "é o operador do
   * Barrier?" — e a credencial de tenant não pode valer pela segunda. Do contrário, qualquer
   * cliente desligaria uma regra de risco para todos os tenants.
   */
  @Test
  void credencialDeTenantNaoAbreEndpointAdministrativo() {
    assertThatThrownBy(
            () -> com(credencial).get().uri("/v1/risk-rules").retrieve().toBodilessEntity())
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(e -> assertThat(statusDoErro(e)).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  /** E a chave de admin, por sua vez, não vale como credencial de tenant. */
  @Test
  void chaveDeAdminNaoAutenticaComoTenant() {
    assertThatThrownBy(
            () -> submete(com("chave-de-admin-de-teste-com-tamanho-ok"), "111.444.777-35", "F"))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(e -> assertThat(statusDoErro(e)).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  // --- Isolamento do cadastro (CMN 4.753) entre tenants -------------------------------------

  private static final String CPF_ALVO = "111.444.777-35";

  private String credencialDe(String tenantId) {
    jdbc.update(
        "INSERT INTO tenants (id, name, active) VALUES (?, ?, true) ON CONFLICT (id) DO NOTHING",
        tenantId,
        tenantId);
    return apiKeyService.issue(tenantId, "integracao").presentedValue();
  }

  private ProfileResponse atualizaCadastro(String apiKey, UpdateProfileRequest corpo) {
    return com(apiKey)
        .put()
        .uri("/v1/subjects/{doc}/profile", CPF_ALVO.replaceAll("\\D", ""))
        .contentType(MediaType.APPLICATION_JSON)
        .body(corpo)
        .retrieve()
        .toEntity(ProfileResponse.class)
        .getBody();
  }

  private static UpdateProfileRequest cadastroCompletoPf() {
    return new UpdateProfileRequest(
        LocalDate.of(1985, 3, 12),
        null,
        "Brasileira",
        "Engenheira",
        new BigDecimal("12000.00"),
        new UpdateProfileRequest.AddressRequest(
            "Rua Confidencial", "42", null, "Centro", "São Paulo", "SP", "01000-000"),
        "11999998888",
        "titular@exemplo.com",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * O achado: {@code PUT .../profile} devolvia o cadastro depois do merge, e um patch vazio não
   * altera nada. Com o vínculo criado por um simples {@code POST /v1/assessments}, duas chamadas
   * entregavam a um parceiro o dossiê do cliente de outro — endereço, telefone, e-mail, nascimento,
   * renda declarada.
   *
   * <p>O critério de pronto não é "a resposta mudou de formato": é que o tenant B <b>não tenha</b>
   * os dados que o tenant A declarou. Por isso a asserção é sobre a completude — se B enxergasse o
   * cadastro de A, o checklist de B viria completo sem B ter declarado nada.
   */
  @Test
  void cadastroDeclaradoPorUmTenantNaoVazaParaOutro() {
    String tenantA = credencialDe("parceiro-a");
    String tenantB = credencialDe("parceiro-b");

    submete(com(tenantA), CPF_ALVO, "Fulano de Tal");
    ProfileResponse deA = atualizaCadastro(tenantA, cadastroCompletoPf());
    assertThat(deA.complete()).isTrue();

    // B cria o próprio vínculo com o mesmo CPF e tenta ler o cadastro com um patch vazio
    submete(com(tenantB), CPF_ALVO, "Fulano de Tal");
    ProfileResponse deB =
        atualizaCadastro(
            tenantB,
            new UpdateProfileRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null));

    assertThat(deB.complete()).isFalse();
    assertThat(deB.missingFields())
        .containsExactlyInAnyOrder("data de nascimento", "nacionalidade", "ocupação", "endereço");
  }

  /** E a escrita de um tenant não altera o cadastro do outro (o vetor de indução de aprovação). */
  @Test
  void escritaDeUmTenantNaoAlteraOCadastroDoOutro() {
    String tenantA = credencialDe("parceiro-c");
    String tenantB = credencialDe("parceiro-d");

    submete(com(tenantA), CPF_ALVO, "Fulano de Tal");
    atualizaCadastro(tenantA, cadastroCompletoPf());

    submete(com(tenantB), CPF_ALVO, "Fulano de Tal");
    atualizaCadastro(
        tenantB,
        new UpdateProfileRequest(
            LocalDate.of(1999, 9, 9), null, "Outra", "Outra", null, null, null, null, null, null,
            null, null, null, null));

    // o cadastro de A continua o que A declarou: patch vazio de A segue completo
    ProfileResponse deA =
        atualizaCadastro(
            tenantA,
            new UpdateProfileRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    assertThat(deA.complete()).isTrue();
  }
}
