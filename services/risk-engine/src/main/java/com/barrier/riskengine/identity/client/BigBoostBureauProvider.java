package com.barrier.riskengine.identity.client;

import com.barrier.commons.mask.Documents;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * StubBureauProvider}; habilitar exige {@code AccessToken}/{@code TokenId} reais (ver
 * application.yml e ADR correspondente).
 *
 * <p>Mapeamento: {@code Result} vazio → NOT_FOUND; {@code Result} não-vazio → MATCH. A API
 * também expõe status do CPF na Receita (regular/irregular) para MISMATCH, mas o campo exato
 * do schema não foi confirmado na doc pública (truncada no exemplo) — a confirmar quando a
 * API key estiver contratada.
 */
@Component
@Order(20) // depois do bureau real de CNPJ (BrasilAPI=10), antes do stub (=100)
@ConditionalOnProperty(name = "barrier.identity.bigboost.enabled", havingValue = "true")
public class BigBoostBureauProvider implements BureauProvider {

  private static final Logger log = LoggerFactory.getLogger(BigBoostBureauProvider.class);

  private final RestClient restClient;

  public BigBoostBureauProvider(@Qualifier("bigBoostRestClient") RestClient restClient) {
    this.restClient = restClient;
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
      log.debug("BigBoost CPF {}: nome={}", Documents.mask(query.documentDigits()), data.name());
      return new BureauResult(BureauResult.Outcome.MATCH, data.name() + " — confirmado na BigBoost");
    } catch (RestClientException e) {
      throw new BureauUnavailableException("BigBoost indisponível: " + e.getMessage(), e);
    }
  }

  @Override
  public String name() {
    return "bigboost";
  }
}
