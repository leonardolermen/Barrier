package com.barrier.riskengine.identity.client.bigboost;

import com.barrier.commons.mask.Documents;
import com.barrier.commons.name.NameSimilarity;
import com.barrier.riskengine.identity.client.*;
import com.barrier.riskengine.identity.domain.PersonProfile;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

/**
 * Bureau real de <b>CPF</b> via BigBoost (BigDataCorp), dataset {@code basic_data} — API
 * pública com doc aberta, self-service (sem CNPJ para contratar). Desligado por padrão
 * ({@code barrier.identity.bigboost.enabled=false}): dev/testes usam o {@link
 * FakeCpfBureauProvider}; habilitar exige {@code AccessToken}/{@code TokenId} reais (ver
 * application.yml e ADR correspondente).
 *
 * <p>Mapeamento: {@code Result} vazio → NOT_FOUND; caso contrário a <b>situação cadastral</b>
 * ({@code TaxIdStatus} + {@code HasObitIndication}) decide primeiro (ver {@link #outcomeOf}), e só
 * um CPF regular chega à comparação de nome.
 */
@Component
@Order(20) // depois do bureau real de CNPJ (BrasilAPI=10), antes do simulado (=100)
@ConditionalOnProperty(name = "barrier.identity.bigboost.enabled", havingValue = "true")
public class BigBoostBureauProvider implements BureauProvider {

  private static final Logger log = LoggerFactory.getLogger(BigBoostBureauProvider.class);

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final double nameThreshold;
  private final boolean storeRawResponse;

  public BigBoostBureauProvider(
      @Qualifier("bigBoostRestClient") RestClient restClient,
      ObjectMapper objectMapper,
      @Value("${barrier.identity.name-match.threshold:0.85}") double nameThreshold,
      @Value("${barrier.identity.store-raw-response:true}") boolean storeRawResponse) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
    this.nameThreshold = nameThreshold;
    this.storeRawResponse = storeRawResponse;
  }

  @Override
  public boolean supports(String documentType) {
    return "CPF".equals(documentType);
  }

  @Override
  public BureauResult check(BureauQuery query) {
    try {
      // O corpo é lido como texto para preservar o rastro: o QueryId identifica a consulta no
      // provedor, e o payload (redigido) é o que permite responder "foi isto que o bureau
      // respondeu naquele dia" sem refazer a consulta, que hoje responderia outra coisa.
      String body =
          restClient
              .post()
              .uri("/pessoas")
              .contentType(MediaType.APPLICATION_JSON)
              .body(BigBoostBasicDataRequest.forCpf(query.documentDigits()))
              .retrieve()
              .body(String.class);
      BureauTrace trace = BureauTrace.from(objectMapper, body, "QueryId", storeRawResponse);
      BigBoostBasicDataResponse response =
          body == null ? null : objectMapper.readValue(body, BigBoostBasicDataResponse.class);

      List<BigBoostBasicDataResponse.ResultItem> results =
          response == null || response.result() == null ? List.of() : response.result();
      if (results.isEmpty()) {
        return new BureauResult(BureauResult.Outcome.NOT_FOUND, "CPF não encontrado na BigBoost")
            .withTrace(trace);
      }
      BigBoostBasicDataResponse.BasicData data = results.get(0).basicData();
      log.debug(
          "BigBoost CPF {}: status={}, obito={}",
          Documents.mask(query.documentDigits()),
          data.taxIdStatus(),
          data.hasObitIndication());

      // A situação cadastral é avaliada ANTES do nome: um CPF de titular falecido com o nome
      // batendo é justamente o caso perigoso, e comparar nome primeiro o aprovaria.
      BureauResult.Outcome byStatus = outcomeOf(data);
      if (byStatus != BureauResult.Outcome.MATCH) {
        return new BureauResult(byStatus, "Situação do CPF na Receita: " + statusLabel(data))
            .withTrace(trace);
      }

      // O CPF existir e estar regular não diz que pertence a quem o informou. Sem esta comparação,
      // um CPF real de terceiro somado a qualquer nome resultava em identidade "verificada".
      if (!NameSimilarity.matches(query.name(), data.name(), nameThreshold)) {
        return new BureauResult(
                BureauResult.Outcome.MISMATCH,
                "Nome informado diverge do titular do CPF (similaridade "
                    + Math.round(NameSimilarity.similarity(query.name(), data.name()) * 100)
                    + "%)")
            .withTrace(trace);
      }
      // O cadastro objetivo que a fonte já conhece não deve ser cobrado do parceiro: sem devolver
      // isto, a avaliação era rebaixada para revisão por "cadastro incompleto" mesmo com o bureau
      // tendo respondido. Ocupação não vem do basic_data — segue sendo declaração do cliente.
      return BureauResult.of(
              BureauResult.Outcome.MATCH,
              data.name() + " — confirmado na BigBoost",
              new PersonProfile(parseBirthDate(data.birthDate()), null, null))
          .withTrace(trace);
    } catch (RestClientException e) {
      throw new BureauUnavailableException("BigBoost indisponível: " + e.getMessage(), e);
    }
  }

  /**
   * Traduz a situação cadastral da Receita para o desfecho do bureau.
   *
   * <p>Fecha o "a confirmar" do ADR-0014: até aqui, {@code Result} não-vazio virava MATCH, e um CPF
   * de titular falecido com o nome batendo era aprovado como identidade verificada.
   *
   * <ul>
   *   <li>óbito (status ou indicação) → {@link BureauResult.Outcome#DECEASED}, bloqueio;
   *   <li>{@code NULA} → NOT_FOUND: o CPF nunca existiu validamente;
   *   <li>{@code SUSPENSA}/{@code CANCELADA}/{@code PENDENTE} → MISMATCH: o titular existe, o
   *       cadastro é que não está apto — é caso de revisão, não de recusa automática;
   *   <li>status ausente/desconhecido → MISMATCH, nunca MATCH. Campo que a API deixou de mandar não
   *       pode ser lido como "está tudo certo" — foi assim que o fail-open nasceu.
   * </ul>
   */
  private static BureauResult.Outcome outcomeOf(BigBoostBasicDataResponse.BasicData data) {
    if (Boolean.TRUE.equals(data.hasObitIndication())) {
      return BureauResult.Outcome.DECEASED;
    }
    String status = data.taxIdStatus() == null ? "" : data.taxIdStatus().trim().toUpperCase();
    if (status.contains("FALECID")) {
      return BureauResult.Outcome.DECEASED;
    }
    if (status.equals("NULA")) {
      return BureauResult.Outcome.NOT_FOUND;
    }
    return status.equals("REGULAR") ? BureauResult.Outcome.MATCH : BureauResult.Outcome.MISMATCH;
  }

  /**
   * Data ilegível ou ausente vira {@code null} em vez de derrubar a verificação: o cadastro fica
   * incompleto e a avaliação vai para revisão, que é o desfecho conservador correto.
   */
  private static LocalDate parseBirthDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
    } catch (DateTimeParseException e) {
      log.warn("Data de nascimento ilegível vinda da BigBoost");
      return null;
    }
  }

  private static String statusLabel(BigBoostBasicDataResponse.BasicData data) {
    String status = data.taxIdStatus() == null ? "não informada" : data.taxIdStatus();
    return Boolean.TRUE.equals(data.hasObitIndication())
        ? status + " (com indicação de óbito)"
        : status;
  }

  @Override
  public String name() {
    return "bigboost";
  }
}
