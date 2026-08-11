package com.barrier.riskengine.identity.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Subconjunto da resposta do dataset {@code basic_data} da API de <b>Empresas</b> da
 * BigBoost/BigDataCorp ({@code POST /empresas}).
 *
 * <p>⚠️ Assim como no dataset de pessoas, o schema foi escrito a partir da documentação e
 * <b>ainda não foi verificado contra a API real</b> (a conta é self-service, mas a verificação
 * depende de credencial contratada). Campos desconhecidos são ignorados e todo campo é opcional no
 * mapeamento — o provider trata ausência como "não sei", nunca como "está tudo certo".
 *
 * @param result lista de empresas casadas pela consulta; vazia significa CNPJ não encontrado
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record BigBoostCompanyResponse(@JsonProperty("Result") List<ResultItem> result) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ResultItem(
      @JsonProperty("MatchKeys") String matchKeys, @JsonProperty("BasicData") BasicData basicData) {}

  /**
   * @param taxIdStatus situação cadastral na Receita: {@code ATIVA}, {@code BAIXADA},
   *     {@code SUSPENSA}, {@code INAPTA}, {@code NULA}
   * @param foundedDate data de abertura, que alimenta a regra de empresa recém-aberta
   * @param activities CNAEs; o principal é o que a regra de CNAE sensível consome
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record BasicData(
      @JsonProperty("TaxIdNumber") String taxIdNumber,
      @JsonProperty("OfficialName") String officialName,
      @JsonProperty("TradeName") String tradeName,
      @JsonProperty("TaxIdStatus") String taxIdStatus,
      @JsonProperty("FoundedDate") String foundedDate,
      @JsonProperty("Activities") List<Activity> activities) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Activity(
      @JsonProperty("Code") String code,
      @JsonProperty("Activity") String description,
      @JsonProperty("IsMain") Boolean main) {}
}
