package com.barrier.riskengine.identity.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;

import com.barrier.riskengine.identity.client.bigboost.BigBoostCnpjBureauProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Mapeamento do dataset {@code basic_data} da API de Empresas da BigBoost.
 *
 * <p>Este bureau existe para a cadeia de PJ deixar de falhar aberto: sem ele, a BrasilAPI fora do
 * ar levava a avaliação de pessoa jurídica direto para o provider simulado.
 */
class BigBoostCnpjBureauProviderTest {

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final BigBoostCnpjBureauProvider provider =
      new BigBoostCnpjBureauProvider(builder.build(), 0.85);

  @Test
  void atendeApenasCnpj() {
    assertThat(provider.supports("CNPJ")).isTrue();
    assertThat(provider.supports("CPF")).isFalse();
  }

  @Test
  void empresaAtivaComNomeCompativelRetornaMatch() {
    responde("ATIVA", "PADARIA DO JOAO LTDA", "Padaria do Joao");

    BureauResult result =
        provider.check(new BureauQuery("CNPJ", "11222333000181", "Padaria do Joao Ltda"));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.MATCH);
    assertThat(result.company()).isNotNull();
    assertThat(result.company().openingDate()).isEqualTo(LocalDate.of(2015, 3, 10));
    assertThat(result.company().cnaeCode()).isEqualTo("4721102");
    server.verify();
  }

  /** O nome fantasia também vale: o cliente informa com frequência o nome pelo qual opera. */
  @Test
  void casaTambemPeloNomeFantasia() {
    responde("ATIVA", "COMERCIO DE ALIMENTOS XYZ LTDA", "Padaria do Joao");

    BureauResult result =
        provider.check(new BureauQuery("CNPJ", "11222333000181", "Padaria do Joao"));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.MATCH);
  }

  @Test
  void nomeDivergenteRetornaMismatch() {
    responde("ATIVA", "PADARIA DO JOAO LTDA", "Padaria do Joao");

    BureauResult result =
        provider.check(new BureauQuery("CNPJ", "11222333000181", "Outra Empresa S.A."));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.MISMATCH);
    assertThat(result.detail()).contains("diverge");
  }

  /**
   * Situação cadastral decide antes do nome, e ausente nunca vira MATCH: campo que a API deixou de
   * mandar lido como "está tudo certo" é exatamente como o fail-open nasce.
   */
  @ParameterizedTest
  @CsvSource({
    "BAIXADA,MISMATCH",
    "SUSPENSA,MISMATCH",
    "INAPTA,MISMATCH",
    "NULA,NOT_FOUND",
    "'',MISMATCH",
    "DESCONHECIDA,MISMATCH"
  })
  void situacaoCadastralDecideAntesDoNome(String status, String esperado) {
    responde(status, "PADARIA DO JOAO LTDA", "Padaria do Joao");

    BureauResult result =
        provider.check(new BureauQuery("CNPJ", "11222333000181", "Padaria do Joao Ltda"));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.valueOf(esperado));
    // o perfil vem mesmo com a empresa irregular: CNAE e idade importam para o risco nos dois casos
    assertThat(result.company()).isNotNull();
  }

  @Test
  void resultadoVazioRetornaNotFound() {
    server
        .expect(requestTo("/empresas"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"Result\":[]}", MediaType.APPLICATION_JSON));

    BureauResult result = provider.check(new BureauQuery("CNPJ", "11222333000181", "Qualquer"));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.NOT_FOUND);
  }

  /** Indisponibilidade vira exceção própria: a cadeia cai para o próximo, não decide errado. */
  @Test
  void erroDoServidorViraIndisponibilidade() {
    server.expect(requestTo("/empresas")).andRespond(withServerError());

    assertThatThrownBy(
            () -> provider.check(new BureauQuery("CNPJ", "11222333000181", "Qualquer")))
        .isInstanceOf(BureauUnavailableException.class);
  }

  /** Data ilegível não pode derrubar a avaliação — vira ausente e a regra de idade não dispara. */
  @Test
  void dataDeAberturaIlegivelNaoQuebra() {
    server
        .expect(requestTo("/empresas"))
        .andRespond(
            withSuccess(
                """
                {"Result":[{"BasicData":{"TaxIdNumber":"11222333000181",
                "OfficialName":"PADARIA DO JOAO LTDA","TradeName":"Padaria",
                "TaxIdStatus":"ATIVA","FoundedDate":"data-invalida","Activities":[]}}]}
                """,
                MediaType.APPLICATION_JSON));

    BureauResult result =
        provider.check(new BureauQuery("CNPJ", "11222333000181", "Padaria do Joao Ltda"));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.MATCH);
    assertThat(result.company().openingDate()).isNull();
    assertThat(result.company().cnaeCode()).isNull();
  }

  private void responde(String status, String razaoSocial, String fantasia) {
    server
        .expect(requestTo("/empresas"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {"Result":[{"MatchKeys":"doc{11222333000181}","BasicData":{
                  "TaxIdNumber":"11222333000181",
                  "OfficialName":"%s",
                  "TradeName":"%s",
                  "TaxIdStatus":"%s",
                  "FoundedDate":"2015-03-10T00:00:00Z",
                  "Activities":[
                    {"Code":"4711302","Activity":"Comércio varejista","IsMain":false},
                    {"Code":"4721102","Activity":"Padaria e confeitaria","IsMain":true}]
                }}]}
                """
                    .formatted(razaoSocial, fantasia, status),
                MediaType.APPLICATION_JSON));
  }
}
