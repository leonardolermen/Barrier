package com.barrier.riskengine.subject.profile.client.serpro;

import com.barrier.riskengine.resilience.CircuitBreaker;
import com.barrier.riskengine.resilience.CircuitBreakerRegistry;
import com.barrier.riskengine.serpro.SerproTokenClient;
import com.barrier.riskengine.subject.profile.client.interfaces.RegistryValidationProvider;
import com.barrier.riskengine.subject.profile.domain.RegistryValidationRequest;
import com.barrier.riskengine.subject.profile.domain.RegistryValidationResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * Validação cadastral via Datavalid/Serpro ({@code POST pessoa-fisica/validacao}): confere dados
 * <b>declarados</b> contra RFB e, para endereço, contra a base da CNH (SENATRAN). Desligado por
 * padrão — dev/testes usam {@link com.barrier.riskengine.subject.profile.client.StubRegistryValidationProvider}.
 * Habilitar exige {@code barrier.serpro.enabled=true} (conectividade compartilhada — ver {@link
 * com.barrier.riskengine.serpro.SerproGatewayConfig}) <b>e</b> {@code
 * barrier.registry-validation.enabled=true} (esta frente específica).
 *
 * <p><b>Não verificado ao vivo</b> — a sondagem desta etapa não teve acesso à rede a partir deste
 * ambiente (sem egress; diferente da etapa de biometria, que rodou contra a demonstração real).
 * Todo o mapeamento de contrato abaixo segue a documentação oficial verbatim, e o tratamento de
 * erro segue a taxonomia já confirmada ao vivo para a biometria ({@link RegistryValidationResultCode}),
 * por analogia — ver relatório.
 *
 * <p><b>{@code privacidade} é config de contrato, não dado de requisição:</b> {@code id_template}
 * (RFB) e {@code token}/{@code cnpj_anuente} (SENATRAN) vêm do cadastro do parceiro junto ao
 * Serpro. A família {@code DV200–DV213} ({@link RegistryValidationResultCode.Classification#CONFIGURATION})
 * é tratada com mensagem apontando para configuração, nunca para o CPF do cliente.
 */
@Component
@ConditionalOnProperty(name = "barrier.registry-validation.enabled", havingValue = "true")
public class SerproRegistryValidationProvider implements RegistryValidationProvider {

  private static final Logger log = LoggerFactory.getLogger(SerproRegistryValidationProvider.class);

  private final RestClient restClient;
  private final SerproTokenClient tokenClient;
  private final ObjectMapper objectMapper;
  private final CircuitBreakerRegistry breakers;
  private final String rfbIdTemplate;
  private final String senatranToken;
  private final String senatranCnpjAnuente;

  public SerproRegistryValidationProvider(
      @Qualifier("serproDatavalidRestClient") RestClient restClient,
      SerproTokenClient tokenClient,
      ObjectMapper objectMapper,
      CircuitBreakerRegistry breakers,
      @Value("${barrier.registry-validation.rfb-id-template}") String rfbIdTemplate,
      @Value("${barrier.registry-validation.senatran-token}") String senatranToken,
      @Value("${barrier.registry-validation.senatran-cnpj-anuente}") String senatranCnpjAnuente) {
    this.restClient = restClient;
    this.tokenClient = tokenClient;
    this.objectMapper = objectMapper;
    this.breakers = breakers;
    this.rfbIdTemplate = rfbIdTemplate;
    this.senatranToken = senatranToken;
    this.senatranCnpjAnuente = senatranCnpjAnuente;
  }

  @Override
  public Optional<RegistryValidationResult> validate(
      UUID subjectId, String tenantId, RegistryValidationRequest request) {
    CircuitBreaker breaker = breakers.forName(name());
    if (!breaker.allowRequest()) {
      log.warn("Disjuntor '{}' aberto; validação cadastral não solicitada", name());
      return Optional.empty();
    }
    try {
      WireRequest wire =
          new WireRequest(
              new WireRequest.Privacidade(
                  new WireRequest.Privacidade.Rfb(rfbIdTemplate),
                  new WireRequest.Privacidade.Senatran(senatranToken, senatranCnpjAnuente)),
              request.cpf(),
              request);
      String body =
          restClient
              .post()
              .uri("/pessoa-fisica/validacao")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenClient.token())
              .contentType(MediaType.APPLICATION_JSON)
              .body(wire)
              .retrieve()
              .body(String.class);
      WireResponse response = objectMapper.readValue(body, WireResponse.class);
      breaker.recordSuccess();
      return Optional.of(toResult(response));
    } catch (RestClientResponseException e) {
      return handleError(subjectId, breaker, e);
    } catch (RestClientException e) {
      breaker.recordFailure();
      log.warn("Falha de transporte na validação cadastral Datavalid: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private Optional<RegistryValidationResult> handleError(
      UUID subjectId, CircuitBreaker breaker, RestClientResponseException e) {
    String body = e.getResponseBodyAsString();
    RegistryValidationResultCode.Classification classification =
        RegistryValidationResultCode.classify(e.getStatusCode().value(), body, objectMapper);
    switch (classification) {
      case CONFIGURATION -> {
        breaker.recordSuccess(); // a integração funciona; o problema é o template/contrato
        log.error(
            "Validação cadastral Datavalid recusada por configuração do template/contrato Serpro"
                + " (não é dado do cliente) para subject {}: verifique privacidade-token/"
                + "senatran-token/cnpj-anuente — code={}",
            subjectId,
            body);
        return Optional.empty();
      }
      case DEFINITIVE_INVALID_DATA, MINOR_SUBJECT -> {
        // A chamada funcionou; o desfecho é que a requisição não pôde ser processada para este
        // CPF. Cobrado (menor de idade inclusive). Sem resultado estruturado para devolver.
        breaker.recordSuccess();
        log.info("Validação cadastral Datavalid: desfecho definitivo para subject {}", subjectId);
        return Optional.empty();
      }
      case TRANSIENT_QUOTA -> {
        log.warn("Cota do Serpro esgotada (429) na validação cadastral do subject {}", subjectId);
        return Optional.empty();
      }
      case TRANSIENT_PROVIDER -> {
        breaker.recordFailure();
        return Optional.empty();
      }
      default -> {
        breaker.recordFailure();
        log.warn(
            "Erro não classificado na validação cadastral Datavalid do subject {}: {}",
            subjectId,
            body);
        return Optional.empty();
      }
    }
  }

  private RegistryValidationResult toResult(WireResponse r) {
    RegistryValidationResult.Rfb rfb =
        r.rfb() == null
            ? null
            : new RegistryValidationResult.Rfb(
                r.rfb().nomeSimilaridade(),
                r.rfb().nomeSocialSimilaridade(),
                r.rfb().situacaoCpf(),
                r.rfb().dataNascimento(),
                r.rfb().dataInscricaoCpf());
    RegistryValidationResult.Cnh cnh =
        r.cnh() == null
            ? null
            : new RegistryValidationResult.Cnh(
                r.cnh().nomeSimilaridade(),
                r.cnh().dataNascimento(),
                r.cnh().sexo(),
                r.cnh().numeroRegistro(),
                r.cnh().categoria(),
                r.cnh().situacao(),
                r.cnh().dataValidade(),
                r.cnh().endereco() == null
                    ? null
                    : new RegistryValidationResult.Endereco(
                        r.cnh().endereco().logradouroSimilaridade(),
                        r.cnh().endereco().cep(),
                        r.cnh().endereco().uf()));
    return new RegistryValidationResult(r.rfbExiste(), r.cnhExiste(), rfb, cnh, "datavalid/v5");
  }

  @Override
  public String name() {
    return "datavalid-serpro-validacao-cadastral";
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record WireRequest(
      Privacidade privacidade, String cpf, RegistryValidationRequest validacao) {

    private record Privacidade(Rfb rfb, Senatran senatran) {
      private record Rfb(@JsonProperty("id_template") String idTemplate) {}

      private record Senatran(String token, @JsonProperty("cnpj_anuente") String cnpjAnuente) {}
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record WireResponse(
      @JsonProperty("rfb_existe") boolean rfbExiste,
      @JsonProperty("cnh_existe") boolean cnhExiste,
      Rfb rfb,
      Cnh cnh) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Rfb(
        @JsonProperty("nome_similaridade") Double nomeSimilaridade,
        @JsonProperty("nome_social_similaridade") Double nomeSocialSimilaridade,
        @JsonProperty("situacao_cpf") Boolean situacaoCpf,
        @JsonProperty("data_nascimento") Boolean dataNascimento,
        @JsonProperty("data_inscricao_cpf") Boolean dataInscricaoCpf) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Cnh(
        @JsonProperty("nome_similaridade") Double nomeSimilaridade,
        @JsonProperty("data_nascimento") Boolean dataNascimento,
        Boolean sexo,
        @JsonProperty("numero_registro") Boolean numeroRegistro,
        Boolean categoria,
        Boolean situacao,
        @JsonProperty("data_validade") Boolean dataValidade,
        Endereco endereco) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Endereco(
        @JsonProperty("logradouro_similaridade") Double logradouroSimilaridade,
        Boolean cep,
        Boolean uf) {}
  }
}
