package com.barrier.riskengine.identity.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Cobre o mapeamento da resposta real do dataset {@code basic_data} (BigBoost) — o JSON de
 * exemplo abaixo é o exemplo oficial da API Reference (dados mascarados pela própria doc).
 */
class BigBoostBureauProviderTest {

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final BigBoostBureauProvider provider = new BigBoostBureauProvider(builder.build(), 0.85);

  @Test
  void cpfEncontradoComNomeCompativelRetornaMatch() {
    respondeComNome("VICTOR NASCIMENTO");

    BureauResult result =
        provider.check(new BureauQuery("CPF", "15000000794", "Victor Nascimento"));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.MATCH);
    assertThat(result.detail()).contains("VICTOR NASCIMENTO");
    server.verify();
  }

  /**
   * Regressão: antes bastava o CPF existir na base para virar MATCH. Um CPF real de terceiro
   * somado a qualquer nome resultava em identidade "verificada".
   */
  @Test
  void cpfEncontradoComNomeDivergenteRetornaMismatch() {
    respondeComNome("VICTOR NASCIMENTO");

    BureauResult result = provider.check(new BureauQuery("CPF", "15000000794", "Fulano de Tal"));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.MISMATCH);
    assertThat(result.detail()).contains("diverge");
    server.verify();
  }

  /**
   * A doc pública da BigBoost mostra o nome <b>mascarado</b> ({@code VIC************NTO}) — é assim
   * que a API responde sem o dataset completo contratado. Nesse formato nenhuma comparação de nome
   * é possível, e o provider tem de tratar como divergência em vez de aprovar: aprovar seria voltar
   * ao "CPF existe logo está verificado". Confirmar o formato real ao contratar as credenciais.
   */
  @Test
  void nomeMascaradoPelaApiNaoConfirmaIdentidade() {
    respondeComNome("VIC************NTO");

    assertThat(provider.check(new BureauQuery("CPF", "15000000794", "Victor Nascimento")).outcome())
        .isEqualTo(BureauResult.Outcome.MISMATCH);
    server.verify();
  }

  private void respondeComNome(String nome) {
    server
        .expect(requestTo("/pessoas"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {
                  "Result": [
                    {
                      "MatchKeys": "doc**********94}",
                      "BasicData": {
                        "TaxIdNumber": "150*****794",
                        "TaxIdCountry": "BRAZIL",
                        "Name": "%s",
                        "Gender": "M"
                      }
                    }
                  ]
                }
                """
                    .formatted(nome),
                MediaType.APPLICATION_JSON));
  }

  @Test
  void cpfNaoEncontradoRetornaNotFound() {
    server
        .expect(requestTo("/pessoas"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"Result\": []}", MediaType.APPLICATION_JSON));

    BureauResult result = provider.check(new BureauQuery("CPF", "99999999999", "Ninguem"));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.NOT_FOUND);
    server.verify();
  }

  @Test
  void indisponibilidadeViraBureauUnavailableException() {
    server.expect(requestTo("/pessoas")).andExpect(method(HttpMethod.POST)).andRespond(withServerError());

    assertThatThrownBy(() -> provider.check(new BureauQuery("CPF", "15000000794", "Fulano")))
        .isInstanceOf(BureauUnavailableException.class);
    server.verify();
  }

  @Test
  void supportsApenasCpf() {
    assertThat(provider.supports("CPF")).isTrue();
    assertThat(provider.supports("CNPJ")).isFalse();
  }
}
