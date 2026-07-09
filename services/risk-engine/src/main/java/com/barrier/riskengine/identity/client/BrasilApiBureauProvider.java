package com.barrier.riskengine.identity.client;

import com.barrier.commons.mask.Documents;
import com.barrier.riskengine.identity.domain.CompanyProfile;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(BrasilApiBureauProvider.class);

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
      log.debug(
          "BrasilAPI CNPJ {}: situacao={}, cnae={} ({}), abertura={}, socios={}",
          Documents.mask(query.documentDigits()),
          situacao,
          cnpj.cnaeFiscal(),
          cnpj.cnae(),
          cnpj.dataInicioAtividade(),
          cnpj.qsa() == null ? 0 : cnpj.qsa().size());
      String detail = cnpj.razaoSocial() + " — " + situacao;
      // O perfil objetivo (abertura/CNAE/QSA) alimenta as regras de risco de PJ; sempre que a
      // empresa existe (ativa ou não) ele é preenchido.
      CompanyProfile profile = toProfile(cnpj);
      return "ATIVA".equalsIgnoreCase(situacao)
          ? new BureauResult(BureauResult.Outcome.MATCH, detail, profile)
          : new BureauResult(BureauResult.Outcome.MISMATCH, "Situação: " + situacao, profile);
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

  private static CompanyProfile toProfile(BrasilApiCnpj cnpj) {
    List<CompanyProfile.Partner> partners =
        cnpj.qsa() == null
            ? List.of()
            : cnpj.qsa().stream().filter(s -> s != null).map(BrasilApiBureauProvider::toPartner).toList();
    String cnaeCode = cnpj.cnaeFiscal() == null ? null : String.valueOf(cnpj.cnaeFiscal());
    return new CompanyProfile(parseDate(cnpj.dataInicioAtividade()), cnaeCode, cnpj.cnae(), partners);
  }

  private static CompanyProfile.Partner toPartner(BrasilApiCnpj.Socio socio) {
    boolean legalEntity = Integer.valueOf(1).equals(socio.identificadorDeSocio());
    boolean foreign =
        Integer.valueOf(3).equals(socio.identificadorDeSocio())
            || (socio.pais() != null
                && !socio.pais().isBlank()
                && !"BRASIL".equalsIgnoreCase(socio.pais().trim()));
    return new CompanyProfile.Partner(
        socio.nomeSocio(), legalEntity, foreign, socio.qualificacaoSocio());
  }

  private static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      return null; // data fora do padrão ISO não derruba a verificação
    }
  }
}
