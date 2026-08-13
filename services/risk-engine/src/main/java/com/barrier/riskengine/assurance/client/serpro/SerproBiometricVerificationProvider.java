package com.barrier.riskengine.assurance.client.serpro;

import com.barrier.commons.mask.Documents;
import com.barrier.riskengine.assurance.client.BiometricSubmission;
import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.resilience.CircuitBreaker;
import com.barrier.riskengine.resilience.CircuitBreakerRegistry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * Biometria facial com prova de vida via Datavalid/Serpro, fluxo por PIN: o cidadão nunca envia
 * foto ao Barrier — captura no app gov.br usando o PIN emitido aqui, e o resultado volta
 * <b>assinado</b> (JWS), verificável contra o JWKS (ver {@link SerproJwsVerifier}).
 *
 * <p>Desligado por padrão ({@code barrier.assurance.serpro.enabled=false}) — mesmo padrão de
 * {@code BigBoostBureauProvider}: dev/testes usam {@code StubBiometricVerificationProvider};
 * habilitar exige {@code SERPRO_CONSUMER_KEY}/{@code SERPRO_CONSUMER_SECRET} reais.
 *
 * <p><b>Limite conhecido, documentado e não resolvido nesta rodada:</b> a associação
 * {@code pin → CPF} necessária para {@link #pollResult} é mantida em memória
 * ({@link #pinToDocument}), não persistida. Um restart da instância entre o PIN ser emitido e o
 * poller trazer o resultado perde essa associação — o check fica {@code PENDING} até expirar e
 * então vira {@code UNAVAILABLE} pelo próprio poller (ver {@code AssuranceResultPoller#expired}),
 * não trava para sempre, mas o cidadão precisa refazer a captura. Persistir o CPF junto do check
 * resolveria, ao custo de guardar CPF numa tabela que hoje não guarda documento nenhum — decisão
 * de produto que ficou fora desta fundação (ver relatório).
 *
 * <p><b>Verificado ao vivo contra o ambiente de demonstração</b> (token fixo público do Serpro
 * para esse fim; ver relatório): a URL base {@code gateway.apiserpro.serpro.gov.br/
 * datavalid-demonstracao/v5} responde (a outra forma documentada,
 * {@code apigateway.serpro.gov.br/.../v2}, não resolveu); o JWKS real tem {@code kid} (ver
 * {@link SerproJwksClient}); o {@code pin} tem <b>exatamente 9 caracteres</b> — descoberto porque
 * um PIN de 6 dígitos devolveu {@code HTTP 400} em <b>texto puro</b> (não JSON):
 * {@code "pin : valor deve possuir exatamente 9 caracteres"}; e a taxonomia de códigos de erro
 * ({@link SerproResultCode}) foi confirmada contra a tabela oficial e um 422/DV170 real. O que
 * <b>não</b> foi verificado ao vivo (cota da demonstração esgotada antes de conseguir emitir um
 * PIN válido): o corpo de sucesso de {@code POST .../app/pin}, o formato de {@code
 * selo_biometrico} além de {@code "A"}, se {@code face_similaridade} sempre vem populado, e o
 * formato de {@code device} — todos seguem só documentados, tratados defensivamente.
 */
@Component
@ConditionalOnProperty(name = "barrier.assurance.serpro.enabled", havingValue = "true")
public class SerproBiometricVerificationProvider implements BiometricVerificationProvider {

  private static final Logger log = LoggerFactory.getLogger(SerproBiometricVerificationProvider.class);

  private final RestClient restClient;
  private final SerproTokenClient tokenClient;
  private final SerproJwsVerifier jwsVerifier;
  private final ObjectMapper objectMapper;
  private final CircuitBreakerRegistry breakers;
  private final Clock clock;
  private final String privacidadeToken;
  private final String cnpjAnuente;
  private final int pinExpiraEmSegundos;
  private final int qtdTentativas;

  private final Map<String, String> pinToDocument = new ConcurrentHashMap<>();

  public SerproBiometricVerificationProvider(
      @Qualifier("serproDatavalidRestClient") RestClient restClient,
      SerproTokenClient tokenClient,
      SerproJwsVerifier jwsVerifier,
      ObjectMapper objectMapper,
      CircuitBreakerRegistry breakers,
      Clock clock,
      @Value("${barrier.assurance.serpro.privacidade-token}") String privacidadeToken,
      @Value("${barrier.assurance.serpro.cnpj-anuente}") String cnpjAnuente,
      @Value("${barrier.assurance.serpro.pin-expira-em:300}") int pinExpiraEmSegundos,
      @Value("${barrier.assurance.serpro.qtd-tentativas:3}") int qtdTentativas) {
    this.restClient = restClient;
    this.tokenClient = tokenClient;
    this.jwsVerifier = jwsVerifier;
    this.objectMapper = objectMapper;
    this.breakers = breakers;
    this.clock = clock;
    this.privacidadeToken = privacidadeToken;
    this.cnpjAnuente = cnpjAnuente;
    this.pinExpiraEmSegundos = pinExpiraEmSegundos;
    this.qtdTentativas = qtdTentativas;
  }

  @Override
  public AssuranceCheck requestVerification(
      UUID subjectId, String tenantId, String document, BiometricSubmission submission) {
    CircuitBreaker breaker = breakers.forName(name());
    if (!breaker.allowRequest()) {
      log.warn("Disjuntor '{}' aberto; PIN biométrico não solicitado", name());
      return unavailable(subjectId, tenantId, submission, "disjuntor aberto");
    }
    try {
      PinRequest request =
          new PinRequest(
              new PinRequest.Privacidade(privacidadeToken, cnpjAnuente),
              document,
              "FACIAL",
              pinExpiraEmSegundos,
              qtdTentativas);
      String body =
          restClient
              .post()
              .uri("/pessoa-fisica/app/pin")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenClient.token())
              .contentType(MediaType.APPLICATION_JSON)
              .body(request)
              .retrieve()
              .body(String.class);
      PinResponse response = objectMapper.readValue(body, PinResponse.class);
      breaker.recordSuccess();
      Instant expiresAt = Instant.parse(response.dataHoraExpiracao());
      pinToDocument.put(response.pin(), document);
      log.info(
          "PIN biométrico emitido para subject {} (CPF {}), expira em {}",
          subjectId,
          Documents.mask(document),
          expiresAt);
      return AssuranceCheck.pendingWithPin(
          UUID.randomUUID(),
          subjectId,
          tenantId,
          name(),
          submission.submittedHash(),
          clock.instant(),
          response.pin(),
          expiresAt);
    } catch (RuntimeException e) {
      breaker.recordFailure();
      log.warn("Falha ao emitir PIN biométrico no Serpro: {}", e.getMessage());
      return unavailable(subjectId, tenantId, submission, e.getMessage());
    }
  }

  /**
   * Consulta o resultado. <b>Repolar enquanto o cidadão não age é grátis</b> — sondagem ao vivo
   * confirmou que só {@code HTTP 200} e {@code 422/DV001} são cobrados; todo o resto, inclusive
   * {@code 422/DV171} (o caso comum enquanto o cidadão não completou a captura), <b>não é
   * cobrado</b>. Contraintuitivo o bastante para registrar aqui: {@code
   * barrier.assurance.poller.delay-ms} pode ser razoavelmente frequente sem custo de consulta —
   * não "otimizar" para menos frequente pensando em economizar chamadas pagas.
   */
  @Override
  public Optional<AssuranceCheck> pollResult(AssuranceCheck pending) {
    if (pending.pin() == null) {
      throw new IllegalStateException(
          "pollResult chamado para check sem PIN (id " + pending.id() + ") — provider errado?");
    }
    String document = pinToDocument.get(pending.pin());
    if (document == null) {
      // Ver Javadoc da classe: perdeu a associação pin→CPF (restart). Sem CPF não há como
      // consultar — devolve UNAVAILABLE em vez de tentar para sempre sem nunca poder resolver.
      log.warn(
          "Associação pin->CPF perdida para o check {} (subject {}); provavelmente restart da"
              + " instância. Marcando indisponível.",
          pending.id(),
          pending.subjectId());
      return Optional.of(unavailableFromPending(pending, "associação pin->CPF perdida (restart)"));
    }

    CircuitBreaker breaker = breakers.forName(name());
    if (!breaker.allowRequest()) {
      // Disjuntor aberto não é "ainda não saiu": é indisponibilidade nossa/do provedor agora.
      // Não resolve o check (fica PENDING, tenta de novo no próximo ciclo) — só recusa chamar.
      return Optional.empty();
    }

    try {
      String body =
          restClient
              .post()
              .uri("/pessoa-fisica/app/resultado")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenClient.token())
              .contentType(MediaType.APPLICATION_JSON)
              .body(new ResultadoRequest(document, pending.pin()))
              .retrieve()
              .body(String.class);
      breaker.recordSuccess();
      ResultadoResponse response = objectMapper.readValue(body, ResultadoResponse.class);
      return Optional.of(resolve(pending, response.jws(), document));
    } catch (RestClientResponseException e) {
      return handleError(pending, breaker, e);
    } catch (RestClientException e) {
      breaker.recordFailure();
      throw e;
    }
  }

  /**
   * Taxonomia completa de {@code SerproResultCode.Classification}, aplicada ao 422/HTTP de erro
   * de {@code /resultado} — ver Javadoc de {@link SerproResultCode} para a divergência entre os
   * dois documentos oficiais do Serpro sobre DV171, e a captura ao vivo da forma do corpo.
   */
  private Optional<AssuranceCheck> handleError(
      AssuranceCheck pending, CircuitBreaker breaker, RestClientResponseException e) {
    String body = e.getResponseBodyAsString();
    SerproResultCode.Classification classification =
        SerproResultCode.classify(e.getStatusCode().value(), body, objectMapper);
    switch (classification) {
      case PENDING_RETRY -> {
        // DV171: "prova de vida ainda não realizada". Não é falha — não alimenta o disjuntor.
        return Optional.empty();
      }
      case TRANSIENT_QUOTA -> {
        // 429 é cota NOSSA, não indisponibilidade do provedor — nunca alimenta o disjuntor, senão
        // esgotar a cota de demonstração abriria o disjuntor e esconderia o problema real.
        log.warn("Cota do Serpro esgotada (429) ao consultar resultado do check {}", pending.id());
        return Optional.empty();
      }
      case DEFINITIVE_FAIL -> {
        breaker.recordSuccess(); // a chamada funcionou; o desfecho é que é negativo
        return Optional.of(outcome(pending, AssuranceOutcome.FAIL, null, "Serpro: " + body));
      }
      case DEFINITIVE_INCONCLUSIVE -> {
        breaker.recordSuccess();
        return Optional.of(outcome(pending, AssuranceOutcome.INCONCLUSIVE, null, "Serpro: " + body));
      }
      case TRANSIENT_PROVIDER -> {
        breaker.recordFailure();
        throw e; // o poller trata como falha transitória e tenta de novo no próximo ciclo
      }
      case NOT_PROVIDER -> {
        // Requisição nossa malformada (4xx que não é do domínio do provedor) — não é
        // indisponibilidade deles, não alimenta o disjuntor, mas também não resolve sozinho.
        log.error("Erro não atribuível ao Serpro consultando resultado do check {}: {}", pending.id(), body);
        throw e;
      }
      default -> {
        // Código desconhecido ou corpo ilegível: cautela — mesmo tratamento de TRANSIENT_PROVIDER,
        // nunca aceito como pendente nem como desfecho.
        breaker.recordFailure();
        throw e;
      }
    }
  }

  private AssuranceCheck resolve(AssuranceCheck pending, String jws, String document) {
    if (jws == null) {
      return unavailableFromPending(pending, "resposta sem jws");
    }
    Optional<SerproJwsVerifier.Payload> payload = jwsVerifier.verify(jws);
    if (payload.isEmpty()) {
      log.warn(
          "Assinatura do JWS biométrico não confere para o check {} (subject {}); tratando como"
              + " indisponível — nunca aceitar desfecho não verificado",
          pending.id(),
          pending.subjectId());
      return unavailableFromPending(pending, "assinatura JWS não verificada");
    }
    SerproJwsVerifier.Payload p = payload.get();
    // sub é o CPF (ver design da task) — nunca no log sem máscara.
    if (p.sub() != null && !p.sub().equals(document)) {
      log.warn(
          "JWS biométrico do check {} veio para CPF diferente do esperado (subject {})",
          pending.id(),
          pending.subjectId());
      return outcome(pending, AssuranceOutcome.FAIL, null, "CPF do JWS diverge do esperado");
    }
    // Único valor observado na doc oficial é "A" (aprovado). Qualquer outro valor — incluindo
    // ausência — é tratado defensivamente como reprovação, nunca como aprovação: um selo
    // desconhecido não pode significar "confiar". Ver relatório: não verificado contra API real.
    boolean approved = "A".equals(p.seloBiometrico());
    Integer score =
        p.faceSimilaridade() == null ? null : (int) Math.round(p.faceSimilaridade() * 100);
    return outcome(
        pending,
        approved ? AssuranceOutcome.PASS : AssuranceOutcome.FAIL,
        score,
        "selo_biometrico=" + p.seloBiometrico());
  }

  private AssuranceCheck unavailableFromPending(AssuranceCheck pending, String detail) {
    return outcome(pending, AssuranceOutcome.UNAVAILABLE, null, detail);
  }

  private AssuranceCheck outcome(
      AssuranceCheck pending, AssuranceOutcome outcome, Integer score, String detail) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        pending.subjectId(),
        pending.tenantId(),
        pending.kind(),
        outcome,
        score,
        name(),
        null,
        "datavalid/v5",
        pending.submittedHash(),
        detail,
        Set.of(),
        clock.instant(),
        pending.consent());
  }

  private AssuranceCheck unavailable(
      UUID subjectId, String tenantId, BiometricSubmission submission, String detail) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        subjectId,
        tenantId,
        com.barrier.riskengine.assurance.domain.AssuranceKind.BIOMETRIC,
        AssuranceOutcome.UNAVAILABLE,
        null,
        name(),
        null,
        "datavalid/v5",
        submission.submittedHash(),
        "Datavalid indisponível: " + detail,
        Set.of(),
        clock.instant(),
        null);
  }

  @Override
  public String name() {
    return "datavalid-serpro";
  }

  /** Corpo verbatim de {@code POST pessoa-fisica/app/pin}. */
  private record PinRequest(
      Privacidade privacidade,
      String cpf,
      String tipo,
      @JsonProperty("expira_em") int expiraEm,
      @JsonProperty("qtd_tentativas") int qtdTentativas) {
    private record Privacidade(String token, @JsonProperty("cnpj_anuente") String cnpjAnuente) {}
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record PinResponse(String pin, @JsonProperty("data_hora_expiracao") String dataHoraExpiracao) {}

  private record ResultadoRequest(String cpf, String pin) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ResultadoResponse(String jws) {}
}
