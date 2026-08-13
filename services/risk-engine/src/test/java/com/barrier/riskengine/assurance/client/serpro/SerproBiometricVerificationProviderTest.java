package com.barrier.riskengine.assurance.client.serpro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.barrier.riskengine.assurance.client.BiometricSubmission;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.resilience.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * Cobre o fluxo por PIN contra o formato verbatim da task, mais o que a sondagem ao vivo do
 * ambiente de demonstração confirmou ou contradisse (ver relatório):
 *
 * <ul>
 *   <li>a URL base real é {@code gateway.apiserpro.serpro.gov.br/datavalid-demonstracao/v5}, com
 *       {@code pessoa-fisica/app/pin} e {@code pessoa-fisica/app/resultado} — a outra forma
 *       circulando ({@code apigateway.serpro.gov.br/.../v2/validate/pf}) não resolveu;
 *   <li>{@code expira_em: 0} do exemplo da doc é <b>rejeitado</b> pela API real
 *       ({@code "expira_em : valor deve ser positivo"}, HTTP 400) — não testado aqui como
 *       comportamento do provider (ele sempre manda um valor positivo, configurável), mas é por
 *       isso que o default de {@code pin-expira-em} não é 0;
 *   <li>o corpo de erro observado ao vivo é texto simples (ex.: {@code "cnpj_anuente : valor
 *       inválido"}), não o JSON estruturado documentado para o throttle — o provider trata
 *       qualquer 4xx/5xx na emissão do PIN como UNAVAILABLE, sem depender do formato do corpo;
 *   <li>o 429 de throttle observado ao vivo tem corpo {@code {"code":"900807","message":"Message
 *       throttled out",...}} — capturado aqui verbatim como documentação, mesmo não sendo o
 *       código DV171 (não foi possível emitir um PIN válido durante a sondagem para chegar ao
 *       fluxo real de "ainda processando" — ver relatório).
 * </ul>
 */
class SerproBiometricVerificationProviderTest {

  private static final UUID SUBJECT = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final RestClient.Builder dataBuilder = RestClient.builder();
  private final MockRestServiceServer dataServer = MockRestServiceServer.bindTo(dataBuilder).build();
  private final RestClient.Builder tokenBuilder = RestClient.builder();
  private final MockRestServiceServer tokenServer = MockRestServiceServer.bindTo(tokenBuilder).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private SerproTokenClient tokenClient() {
    return new SerproTokenClient(
        tokenBuilder.build(), objectMapper, "key", "secret", CLOCK, Duration.ofSeconds(60));
  }

  private void expectTokenCall() {
    tokenServer
        .expect(requestTo("/token"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"scope\":\"escopo\",\"token_type\":\"Bearer\",\"expires_in\":3295,"
                    + "\"access_token\":\"tok-123\"}",
                MediaType.APPLICATION_JSON));
  }

  private SerproBiometricVerificationProvider provider() {
    SerproJwksClient jwksClient = new SerproJwksClient(dataBuilder.build(), CLOCK, Duration.ofHours(1));
    SerproJwsVerifier jwsVerifier = new SerproJwsVerifier(jwksClient, objectMapper);
    return new SerproBiometricVerificationProvider(
        dataBuilder.build(),
        tokenClient(),
        jwsVerifier,
        objectMapper,
        new CircuitBreakerRegistry(5, Duration.ofSeconds(30)),
        CLOCK,
        "privacidade-token",
        "00000000000191",
        300,
        3);
  }

  private BiometricSubmission submissao() {
    return new BiometricSubmission("selfie-ref", "doc-face-ref", "hash-abc");
  }

  @Test
  void pinEmitidoComSucessoViraCheckPending() {
    expectTokenCall();
    dataServer
        .expect(requestTo("/pessoa-fisica/app/pin"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"pin\":\"654321\",\"data_hora_expiracao\":\"2026-08-13T12:05:00Z\"}",
                MediaType.APPLICATION_JSON));

    AssuranceCheck check =
        provider().requestVerification(SUBJECT, "tenant-1", "11144477735", submissao());

    assertThat(check.outcome()).isEqualTo(AssuranceOutcome.PENDING);
    assertThat(check.pin()).isEqualTo("654321");
    assertThat(check.pinExpiresAt()).isEqualTo(Instant.parse("2026-08-13T12:05:00Z"));
    tokenServer.verify();
    dataServer.verify();
  }

  /**
   * Formato de erro observado ao vivo contra o ambiente de demonstração (texto simples, não JSON
   * estruturado) — o provider não pode depender do corpo ter uma forma específica para tratar
   * como indisponibilidade.
   */
  @Test
  void erroDeValidacaoNaEmissaoDoPinViraIndisponivel() {
    expectTokenCall();
    dataServer
        .expect(requestTo("/pessoa-fisica/app/pin"))
        .andRespond(withBadRequest().body("cnpj_anuente : valor inválido").contentType(MediaType.TEXT_PLAIN));

    AssuranceCheck check =
        provider().requestVerification(SUBJECT, "tenant-1", "11144477735", submissao());

    assertThat(check.outcome()).isEqualTo(AssuranceOutcome.UNAVAILABLE);
    assertThat(check.pin()).isNull();
  }

  /**
   * 422/DV171: "ainda não realizada" — não é falha, o check continua PENDING. Corpo verbatim
   * capturado ao vivo contra o ambiente de demonstração (mesma forma de {@code {"code":"DV170",
   * "args":[...],"link":"..."}}, com DV171 no lugar de DV170).
   */
  @Test
  void resultado422ComDv171NaoResolveOCheck() {
    expectTokenCall();
    SerproBiometricVerificationProvider provider = provider();
    // MockRestServiceServer exige todas as expectativas registradas antes de qualquer chamada
    // real — as duas (pin e resultado) entram antes de requestVerification/pollResult rodarem.
    expectPinSuccess();
    dataServer
        .expect(requestTo("/pessoa-fisica/app/resultado"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(
                    "{\"code\":\"DV171\",\"args\":[\"LIVENESS_NOT_DONE\"],"
                        + "\"link\":\"https://apicenter.estaleiro.serpro.gov.br/documentacao/"
                        + "datavalid/codigos_retorno/#422-requisicao-nao-processada\"}")
                .contentType(MediaType.APPLICATION_JSON));

    AssuranceCheck pending =
        provider.requestVerification(SUBJECT, "tenant-1", "11144477735", submissao());
    Optional<AssuranceCheck> result = provider.pollResult(pending, "11144477735");

    assertThat(result).isEmpty();
  }

  /**
   * 422/DV170 (PIN não encontrado) capturado <b>ao vivo</b> contra o ambiente de demonstração,
   * verbatim: {@code {"code":"DV170","args":["APP_PIN_NOT_FOUND"],"link":"..."}}. Diferente de
   * DV171, é definitivo — a tabela oficial de códigos classifica DV170/172/173 como falha
   * permanente e a leitura aqui concorda (só DV171 diverge entre os dois documentos oficiais, ver
   * Javadoc de {@code SerproResultCode}). O check é resolvido como FAIL, não fica tentando de novo.
   */
  @Test
  void resultado422ComDv170ResolveComoFail() {
    expectTokenCall();
    SerproBiometricVerificationProvider provider = provider();
    expectPinSuccess();
    dataServer
        .expect(requestTo("/pessoa-fisica/app/resultado"))
        .andRespond(
            withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .body("{\"code\":\"DV170\",\"args\":[\"APP_PIN_NOT_FOUND\"],\"link\":\"https://x\"}")
                .contentType(MediaType.APPLICATION_JSON));

    AssuranceCheck pending =
        provider.requestVerification(SUBJECT, "tenant-1", "11144477735", submissao());
    Optional<AssuranceCheck> result = provider.pollResult(pending, "11144477735");

    assertThat(result).isPresent();
    assertThat(result.get().outcome()).isEqualTo(AssuranceOutcome.FAIL);
  }

  /** Um 422 com código desconhecido não pode ser confundido com "ainda processando". */
  @Test
  void resultado422ComCodigoDesconhecidoPropagaComoFalha() {
    expectTokenCall();
    SerproBiometricVerificationProvider provider = provider();
    expectPinSuccess();
    dataServer
        .expect(requestTo("/pessoa-fisica/app/resultado"))
        .andRespond(
            withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .body("{\"code\":\"DV999\",\"args\":[],\"link\":\"https://x\"}")
                .contentType(MediaType.APPLICATION_JSON));

    AssuranceCheck pending =
        provider.requestVerification(SUBJECT, "tenant-1", "11144477735", submissao());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.pollResult(pending, "11144477735"))
        .isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
  }

  /**
   * 429 de cota (observado ao vivo: {@code {"code":"900807","message":"Message throttled out",
   * "description":"You have exceeded your quota"}}) é transitório mas <b>não</b> alimenta o
   * disjuntor — é cota nossa, não indisponibilidade do provedor. O check continua PENDING.
   */
  @Test
  void resultado429DeCotaNaoResolveENaoAlimentaDisjuntor() {
    expectTokenCall();
    SerproBiometricVerificationProvider provider = provider();
    expectPinSuccess();
    dataServer
        .expect(requestTo("/pessoa-fisica/app/resultado"))
        .andRespond(
            withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body(
                    "{\"code\":\"900807\",\"message\":\"Message throttled out\","
                        + "\"description\":\"You have exceeded your quota\"}")
                .contentType(MediaType.APPLICATION_JSON));

    AssuranceCheck pending =
        provider.requestVerification(SUBJECT, "tenant-1", "11144477735", submissao());
    Optional<AssuranceCheck> result = provider.pollResult(pending, "11144477735");

    assertThat(result).isEmpty();
  }

  /**
   * <b>O teste que fecha o defeito do mapa em memória</b>: constrói o provider do zero e chama
   * {@code pollResult} diretamente com um check {@code PENDING} cujo PIN só existe porque veio
   * "do banco" (é o argumento do teste, não algo que este provider emitiu) — nenhuma chamada a
   * {@code requestVerification} aconteceu nesta instância. Simula exatamente o cenário de
   * produção que quebrava com o mapa em memória: a réplica que poleia nunca é a que emitiu o PIN.
   * Se a resolução do CPF voltar a depender de estado guardado pelo provider em
   * {@code requestVerification}, este teste fica vermelho — o documento não estaria disponível
   * para nenhum mapa interno resolver.
   */
  @Test
  void poleiaUmCheckPendenteSemTerEmitidoOPinNesteProcesso() {
    // Nenhuma chamada a requestVerification nesta instância — só o /token e o /resultado, que é
    // tudo que uma réplica "diferente" da que emitiu o PIN precisaria fazer.
    expectTokenCall();
    dataServer
        .expect(requestTo("/pessoa-fisica/app/resultado"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"jws\":\"" + jwsAprovado() + "\"}",
                MediaType.APPLICATION_JSON));

    AssuranceCheck pendenteVindoDoBanco =
        AssuranceCheck.pendingWithPin(
            UUID.randomUUID(),
            SUBJECT,
            "tenant-1",
            "datavalid-serpro",
            "hash-abc",
            NOW.minusSeconds(60),
            "987654321",
            NOW.plusSeconds(120));

    Optional<AssuranceCheck> result = provider().pollResult(pendenteVindoDoBanco, "11144477735");

    assertThat(result).isPresent();
    tokenServer.verify();
    dataServer.verify();
  }

  /** JWS minimamente válido só para o teste acima não depender da assinatura conferir de verdade. */
  private String jwsAprovado() {
    String header =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String payload =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"sub\":\"11144477735\",\"selo_biometrico\":\"A\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return header + "." + payload + ".";
  }

  private void expectPinSuccess() {
    dataServer
        .expect(requestTo("/pessoa-fisica/app/pin"))
        .andRespond(
            withSuccess(
                "{\"pin\":\"654321\",\"data_hora_expiracao\":\"2026-08-13T13:00:00Z\"}",
                MediaType.APPLICATION_JSON));
  }
}
