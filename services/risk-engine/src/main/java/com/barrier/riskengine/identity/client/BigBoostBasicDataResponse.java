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

  /**
   * @param taxIdStatus situação cadastral na Receita: {@code REGULAR},
   *     {@code PENDENTE DE REGULARIZACAO}, {@code SUSPENSA}, {@code CANCELADA},
   *     {@code TITULAR FALECIDO}, {@code NULA}
   * @param hasObitIndication indicação de óbito agregada de outras fontes. Conferido
   *     <b>independentemente</b> do status: a BigBoost agrega óbito de fontes que costumam
   *     registrar antes de a Receita atualizar o cadastro, então {@code REGULAR} com indicação de
   *     óbito é caso real — e o mais perigoso dos dois
   * @param motherName nome da mãe; segundo fator clássico de identidade. Ainda não consumido —
   *     quando for, comparar e guardar apenas o <b>resultado</b> da comparação, nunca o valor
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record BasicData(
      @JsonProperty("TaxIdNumber") String taxIdNumber,
      @JsonProperty("Name") String name,
      @JsonProperty("Gender") String gender,
      @JsonProperty("TaxIdStatus") String taxIdStatus,
      @JsonProperty("HasObitIndication") Boolean hasObitIndication,
      @JsonProperty("MotherName") String motherName,
      @JsonProperty("BirthDate") String birthDate) {}
}
