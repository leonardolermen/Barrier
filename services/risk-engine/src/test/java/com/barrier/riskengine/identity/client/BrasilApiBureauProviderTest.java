package com.barrier.riskengine.identity.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BrasilApiBureauProviderTest {

  private MockRestServiceServer server;
  private BrasilApiBureauProvider provider;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    provider = new BrasilApiBureauProvider(builder.build());
  }

  private BureauQuery cnpj() {
    return new BureauQuery("CNPJ", "11222333000181", "Empresa X");
  }

  @Test
  void suportaApenasCnpj() {
    assertThat(provider.supports("CNPJ")).isTrue();
    assertThat(provider.supports("CPF")).isFalse();
  }

  @Test
  void situacaoAtivaViraMatch() {
    server
        .expect(requestTo("/api/cnpj/v1/11222333000181"))
        .andRespond(
            withSuccess(
                "{\"razao_social\":\"ACME LTDA\",\"descricao_situacao_cadastral\":\"ATIVA\"}",
                MediaType.APPLICATION_JSON));

    BureauResult result = provider.check(cnpj());

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.MATCH);
    assertThat(result.detail()).contains("ACME");
  }

  @Test
  void extraiPerfilDaPjParaAsRegrasDeRisco() {
    server
        .expect(requestTo("/api/cnpj/v1/11222333000181"))
        .andRespond(
            withSuccess(
                """
                {"razao_social":"ACME LTDA","descricao_situacao_cadastral":"ATIVA",
                 "cnae_fiscal":6619302,"cnae_fiscal_descricao":"Servicos financeiros",
                 "data_inicio_atividade":"2026-05-10",
                 "qsa":[{"nome_socio":"JOHN DOE","identificador_de_socio":3,
                         "qualificacao_socio":"Socio Estrangeiro","pais":"ESTADOS UNIDOS"}]}
                """,
                MediaType.APPLICATION_JSON));

    var company = provider.check(cnpj()).company();

    assertThat(company).isNotNull();
    assertThat(company.cnaeCode()).isEqualTo("6619302");
    assertThat(company.openingDate().toString()).isEqualTo("2026-05-10");
    assertThat(company.partners()).hasSize(1);
    assertThat(company.partners().get(0).foreign()).isTrue();
  }

  @Test
  void situacaoBaixadaViraMismatch() {
    server
        .expect(requestTo("/api/cnpj/v1/11222333000181"))
        .andRespond(
            withSuccess(
                "{\"razao_social\":\"ACME LTDA\",\"descricao_situacao_cadastral\":\"BAIXADA\"}",
                MediaType.APPLICATION_JSON));

    assertThat(provider.check(cnpj()).outcome()).isEqualTo(BureauResult.Outcome.MISMATCH);
  }

  @Test
  void cnpjInexistenteViraNotFound() {
    server.expect(requestTo("/api/cnpj/v1/11222333000181")).andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(provider.check(cnpj()).outcome()).isEqualTo(BureauResult.Outcome.NOT_FOUND);
  }

  @Test
  void erroDoServidorViraIndisponivel() {
    server
        .expect(requestTo("/api/cnpj/v1/11222333000181"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    assertThatThrownBy(() -> provider.check(cnpj()))
        .isInstanceOf(BureauUnavailableException.class);
  }
}
