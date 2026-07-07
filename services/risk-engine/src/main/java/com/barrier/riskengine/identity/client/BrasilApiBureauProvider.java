package com.barrier.riskengine.identity.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Bureau real de CNPJ via BrasilAPI (dados da Receita Federal, público e sem chave).
 *
 * <p>Mapeamento: situação ATIVA → MATCH; CNPJ inexistente (404) → NOT_FOUND; existente mas
 * não-ativo (baixada/suspensa/inapta/nula) → MISMATCH. Timeout/erro de rede/5xx →
 * {@link BureauUnavailableException} (não derruba a avaliação).
 */
@Component
@Order(10) // bureau real tem prioridade sobre stubs na cadeia de fallback
public class BrasilApiBureauProvider implements BureauProvider {

  private final RestClient restClient;

  public BrasilApiBureauProvider(@Qualifier("brasilApiRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public boolean supports(String documentType) {
    return "CNPJ".equals(documentType);
  }

  @Override
  public BureauResult check(BureauQuery query) {
    try {
      BrasilApiCnpj cnpj =
          restClient
              .get()
              .uri("/api/cnpj/v1/{cnpj}", query.documentDigits())
              .retrieve()
              .body(BrasilApiCnpj.class);

      if (cnpj == null || cnpj.situacaoCadastral() == null) {
        return new BureauResult(BureauResult.Outcome.NOT_FOUND, "CNPJ sem situação cadastral");
      }
      String situacao = cnpj.situacaoCadastral();
      String detail = cnpj.razaoSocial() + " — " + situacao;
      return "ATIVA".equalsIgnoreCase(situacao)
          ? new BureauResult(BureauResult.Outcome.MATCH, detail)
          : new BureauResult(BureauResult.Outcome.MISMATCH, "Situação: " + situacao);
    } catch (HttpClientErrorException.NotFound e) {
      return new BureauResult(BureauResult.Outcome.NOT_FOUND, "CNPJ não encontrado");
    } catch (RestClientException e) {
      throw new BureauUnavailableException("BrasilAPI indisponível: " + e.getMessage(), e);
    }
  }

  @Override
  public String name() {
    return "brasilapi";
  }
}
