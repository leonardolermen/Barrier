package com.barrier.riskengine.identity.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Subconjunto da resposta do dataset {@code basic_data} (BigBoost/BigDataCorp,
 * {@code POST /pessoas}). Schema conforme a API Reference oficial (endpoint "Dados Básicos" —
 * API de Pessoas); campos não usados pelo bureau são ignorados na deserialização.
 *
 * @see <a href="https://docs.bigdatacorp.com.br/plataforma/reference/pessoas-dados-cadastrais-basicos">
 *     Dados Básicos — API Reference BigDataCorp</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record BigBoostBasicDataResponse(@JsonProperty("Result") List<ResultItem> result) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ResultItem(
      @JsonProperty("MatchKeys") String matchKeys, @JsonProperty("BasicData") BasicData basicData) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record BasicData(
      @JsonProperty("TaxIdNumber") String taxIdNumber,
      @JsonProperty("Name") String name,
      @JsonProperty("Gender") String gender) {}
}
