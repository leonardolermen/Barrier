package com.barrier.riskengine.identity.client;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Subconjunto da resposta de CNPJ da BrasilAPI (dados da Receita Federal). */
record BrasilApiCnpj(
    @JsonProperty("razao_social") String razaoSocial,
    @JsonProperty("descricao_situacao_cadastral") String situacaoCadastral,
    @JsonProperty("cnae_fiscal_descricao") String cnae,
    @JsonProperty("data_inicio_atividade") String dataInicioAtividade) {}
