package com.barrier.riskengine.subject.profile.client.serpro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.barrier.riskengine.resilience.CircuitBreakerRegistry;
import com.barrier.riskengine.serpro.SerproTokenClient;
import com.barrier.riskengine.subject.profile.domain.RegistryValidationRequest;
import com.barrier.riskengine.subject.profile.domain.RegistryValidationResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Não verificado ao vivo (sem egress de rede neste ambiente — ver relatório). Cobre o contrato
 * documentado verbatim e a taxonomia de erro por analogia com a já sondada para a biometria.
 */
class SerproRegistryValidationProviderTest {

  private static final UUID SUBJECT = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final RestClient.Builder dataBuilder = RestClient.builder();
  private final MockRestServiceServer dataServer = MockRestServiceServer.bindTo(dataBuilder).build();
  private final RestClient.Builder tokenBuilder = RestClient.builder();
  private final MockRestServiceServer tokenServer = MockRestServiceServer.bindTo(tokenBuilder).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private SerproRegistryValidationProvider provider() {
    SerproTokenClient tokenClient =
        new SerproTokenClient(
            tokenBuilder.build(), objectMapper, "key", "secret", CLOCK, Duration.ofSeconds(60));
    tokenServer
        .expect(requestTo("/token"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"scope\":\"x\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
                    + "\"access_token\":\"tok123\"}",
                MediaType.APPLICATION_JSON));
    return new SerproRegistryValidationProvider(
        dataBuilder.build(),
        tokenClient,
        objectMapper,
        new CircuitBreakerRegistry(5, Duration.ofSeconds(30)),
        "id-template-1",
        "senatran-token-1",
        "12345678000199");
  }

  private static RegistryValidationRequest minimo(String cpf) {
    return new RegistryValidationRequest(
        cpf, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void soEnviaOsCamposDeclarados() {
    RegistryValidationRequest request =
        new RegistryValidationRequest(
            "11122233396",
            "Fulano de Tal",
            LocalDate.of(1990, 5, 20),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    dataServer
        .expect(requestTo("/pessoa-fisica/validacao"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sexo"))))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("11122233396")))
        .andRespond(withSuccess("{\"rfb_existe\":true,\"cnh_existe\":false}", MediaType.APPLICATION_JSON));

    Optional<RegistryValidationResult> result = provider().validate(SUBJECT, "t1", request);

    assertThat(result).isPresent();
    assertThat(result.get().rfbExiste()).isTrue();
    assertThat(result.get().cnhExiste()).isFalse();
  }

  @Test
  void mapeiaRespostaDeSucessoCompleta() {
    String body =
        "{\"rfb_existe\":true,\"cnh_existe\":true,"
            + "\"rfb\":{\"nome_similaridade\":0.98,\"situacao_cpf\":true,\"data_nascimento\":true,"
            + "\"data_inscricao_cpf\":true},"
            + "\"cnh\":{\"nome_similaridade\":0.95,\"data_nascimento\":true,\"sexo\":true,"
            + "\"numero_registro\":true,\"categoria\":true,\"situacao\":true,\"data_validade\":true,"
            + "\"endereco\":{\"logradouro_similaridade\":0.9,\"cep\":true,\"uf\":true}}}";
    dataServer.expect(requestTo("/pessoa-fisica/validacao")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    RegistryValidationResult result = provider().validate(SUBJECT, "t1", minimo("11122233396")).orElseThrow();

    assertThat(result.rfb().situacaoCpf()).isTrue();
    assertThat(result.cnh().endereco().cep()).isTrue();
    assertThat(result.cnh().endereco().uf()).isTrue();
  }

  @Test
  void codigoDeConfiguracaoNaoVazaComoErroDeDado() {
    dataServer
        .expect(requestTo("/pessoa-fisica/validacao"))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).body("{\"code\":\"DV200\"}"));

    Optional<RegistryValidationResult> result = provider().validate(SUBJECT, "t1", minimo("11122233396"));

    assertThat(result).isEmpty();
  }

  @Test
  void cpfInvalidoNaoAlimentaODisjuntor() {
    dataServer
        .expect(requestTo("/pessoa-fisica/validacao"))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).body("{\"code\":\"DV010\"}"));

    Optional<RegistryValidationResult> result = provider().validate(SUBJECT, "t1", minimo("00000000000"));

    assertThat(result).isEmpty();
  }

  @Test
  void cotaEsgotadaNaoAlimentaODisjuntor() {
    dataServer.expect(requestTo("/pessoa-fisica/validacao")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    assertThat(provider().validate(SUBJECT, "t1", minimo("11122233396"))).isEmpty();
  }

  @Test
  void erroTransitorioAlimentaODisjuntor() {
    SerproRegistryValidationProvider provider = provider();
    for (int i = 0; i < 5; i++) {
      dataServer
          .expect(requestTo("/pessoa-fisica/validacao"))
          .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    }
    for (int i = 0; i < 5; i++) {
      assertThat(provider.validate(SUBJECT, "t1", minimo("11122233396"))).isEmpty();
    }

    // sexta chamada nem sai: disjuntor aberto
    assertThat(provider.validate(SUBJECT, "t1", minimo("11122233396"))).isEmpty();
    dataServer.verify();
  }
}
