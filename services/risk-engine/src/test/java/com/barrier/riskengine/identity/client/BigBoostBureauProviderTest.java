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
  private final BigBoostBureauProvider provider = new BigBoostBureauProvider(builder.build());

  @Test
  void cpfEncontradoRetornaMatch() {
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
                        "Name": "VIC************NTO",
                        "Gender": "M"
                      }
                    }
                  ]
                }
                """,
                MediaType.APPLICATION_JSON));

    BureauResult result = provider.check(new BureauQuery("CPF", "15000000794", "Fulano"));

    assertThat(result.outcome()).isEqualTo(BureauResult.Outcome.MATCH);
    assertThat(result.detail()).contains("VIC************NTO");
    server.verify();
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
