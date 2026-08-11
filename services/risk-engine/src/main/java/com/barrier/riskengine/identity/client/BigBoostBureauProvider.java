package com.barrier.riskengine.identity.client;

import com.barrier.commons.mask.Documents;
import com.barrier.commons.name.NameSimilarity;
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
  private final double nameThreshold;

  public BigBoostBureauProvider(
      @Qualifier("bigBoostRestClient") RestClient restClient,
      @Value("${barrier.identity.name-match.threshold:0.85}") double nameThreshold) {
    this.restClient = restClient;
    this.nameThreshold = nameThreshold;
  }

  @Override
  public boolean supports(String documentType) {
    return "CPF".equals(documentType);
  }

  @Override
  public BureauResult check(BureauQuery query) {
    try {
      BigBoostBasicDataResponse response =
          restClient
              .post()
              .uri("/pessoas")
              .contentType(MediaType.APPLICATION_JSON)
              .body(BigBoostBasicDataRequest.forCpf(query.documentDigits()))
              .retrieve()
              .body(BigBoostBasicDataResponse.class);

      List<BigBoostBasicDataResponse.ResultItem> results =
          response == null || response.result() == null ? List.of() : response.result();
      if (results.isEmpty()) {
        return new BureauResult(BureauResult.Outcome.NOT_FOUND, "CPF não encontrado na BigBoost");
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
        return new BureauResult(byStatus, "Situação do CPF na Receita: " + statusLabel(data));
      }

      // O CPF existir e estar regular não diz que pertence a quem o informou. Sem esta comparação,
      // um CPF real de terceiro somado a qualquer nome resultava em identidade "verificada".
      if (!NameSimilarity.matches(query.name(), data.name(), nameThreshold)) {
        return new BureauResult(
            BureauResult.Outcome.MISMATCH,
            "Nome informado diverge do titular do CPF (similaridade "
                + Math.round(NameSimilarity.similarity(query.name(), data.name()) * 100)
                + "%)");
      }
      return new BureauResult(BureauResult.Outcome.MATCH, data.name() + " — confirmado na BigBoost");
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
