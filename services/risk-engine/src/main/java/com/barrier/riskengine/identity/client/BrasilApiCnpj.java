package com.barrier.riskengine.identity.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Subconjunto da resposta de CNPJ da BrasilAPI (dados da Receita Federal). */
record BrasilApiCnpj(
    @JsonProperty("razao_social") String razaoSocial,
    @JsonProperty("descricao_situacao_cadastral") String situacaoCadastral,
    @JsonProperty("cnae_fiscal") Integer cnaeFiscal,
    @JsonProperty("cnae_fiscal_descricao") String cnae,
    @JsonProperty("data_inicio_atividade") String dataInicioAtividade,
    @JsonProperty("qsa") List<Socio> qsa) {

  /**
   * Item do quadro societário (QSA). {@code identificador_de_socio}: 1 = PJ, 2 = PF,
   * 3 = estrangeiro (padrão da Receita); {@code pais} vem preenchido para sócio no exterior.
   */
  record Socio(
      @JsonProperty("nome_socio") String nomeSocio,
      @JsonProperty("identificador_de_socio") Integer identificadorDeSocio,
      @JsonProperty("qualificacao_socio") String qualificacaoSocio,
      @JsonProperty("pais") String pais) {}
}
