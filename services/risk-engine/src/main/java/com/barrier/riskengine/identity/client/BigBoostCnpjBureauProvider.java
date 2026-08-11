package com.barrier.riskengine.identity.client;

import com.barrier.commons.mask.Documents;
import com.barrier.commons.name.NameSimilarity;
import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Bureau real de <b>CNPJ</b> via BigBoost (BigDataCorp), dataset {@code basic_data} da API de
 * Empresas.
 *
 * <p>Existe porque a cadeia de PJ falhava aberto: a BrasilAPI é da fase de teste, e com ela fora do
 * ar a avaliação de pessoa jurídica caía no provider simulado — verificação fictícia sem que nada
 * falhasse. Com este provider, a queda da BrasilAPI cai em outro bureau <b>real</b>; sem nenhum
 * deles, o desfecho é indisponibilidade, que vira revisão humana.
 *
 * <p>Desligado por padrão (mesma flag e credenciais do bureau de CPF). Mapeamento: {@code Result}
 * vazio → NOT_FOUND; situação cadastral decide antes do nome; empresa que existe mas não está
 * {@code ATIVA} → MISMATCH (é caso de revisão, não de recusa automática); situação ausente →
 * MISMATCH, nunca MATCH.
 *
 * <p>⚠️ O QSA <b>não</b> vem no {@code basic_data} — depende de outro dataset da BigDataCorp. Então
 * uma avaliação atendida por este provider entrega {@link CompanyProfile} com abertura e CNAE, mas
 * com quadro societário vazio, e a regra de estrutura societária (KYB de 1º grau) não tem o que
 * avaliar. Está registrado como item aberto no plano de remediação.
 */
@Component
@Order(20) // depois da BrasilAPI (=10), antes do simulado (=100)
@ConditionalOnProperty(name = "barrier.identity.bigboost.enabled", havingValue = "true")
public class BigBoostCnpjBureauProvider implements BureauProvider {

  private static final Logger log = LoggerFactory.getLogger(BigBoostCnpjBureauProvider.class);

  private final RestClient restClient;
  private final double nameThreshold;

  public BigBoostCnpjBureauProvider(
      @Qualifier("bigBoostRestClient") RestClient restClient,
      @Value("${barrier.identity.name-match.threshold:0.85}") double nameThreshold) {
    this.restClient = restClient;
    this.nameThreshold = nameThreshold;
  }

  @Override
  public boolean supports(String documentType) {
    return "CNPJ".equals(documentType);
  }

  @Override
  public BureauResult check(BureauQuery query) {
    try {
      BigBoostCompanyResponse response =
          restClient
              .post()
              .uri("/empresas")
              .contentType(MediaType.APPLICATION_JSON)
              .body(new CompanyRequest("basic_data", "doc{" + query.documentDigits() + "}", 1))
              .retrieve()
              .body(BigBoostCompanyResponse.class);

      List<BigBoostCompanyResponse.ResultItem> results =
          response == null || response.result() == null ? List.of() : response.result();
      if (results.isEmpty()) {
        return new BureauResult(BureauResult.Outcome.NOT_FOUND, "CNPJ não encontrado na BigBoost");
      }
      BigBoostCompanyResponse.BasicData data = results.get(0).basicData();
      if (data == null) {
        return new BureauResult(BureauResult.Outcome.NOT_FOUND, "CNPJ sem dados básicos na BigBoost");
      }
      log.debug(
          "BigBoost CNPJ {}: status={}, abertura={}, cnae={}",
          Documents.mask(query.documentDigits()),
          data.taxIdStatus(),
          data.foundedDate(),
          mainActivity(data) == null ? null : mainActivity(data).code());

      // O perfil objetivo alimenta as regras de PJ e é preenchido mesmo quando a empresa não está
      // ativa: a decisão de risco quer saber CNAE e idade da empresa nos dois casos.
      CompanyProfile profile = toProfile(data);

      BureauResult.Outcome byStatus = outcomeOf(data.taxIdStatus());
      if (byStatus != BureauResult.Outcome.MATCH) {
        return new BureauResult(
            byStatus,
            "Situação do CNPJ na Receita: "
                + (data.taxIdStatus() == null ? "não informada" : data.taxIdStatus()),
            profile);
      }
      if (!nameMatches(query.name(), data)) {
        return new BureauResult(
            BureauResult.Outcome.MISMATCH,
            "Nome informado diverge da empresa (razão social: " + data.officialName() + ")",
            profile);
      }
      return new BureauResult(
          BureauResult.Outcome.MATCH, data.officialName() + " — confirmado na BigBoost", profile);
    } catch (RestClientException e) {
      throw new BureauUnavailableException("BigBoost (empresas) indisponível: " + e.getMessage(), e);
    }
  }

  /**
   * Casa contra razão social <b>ou</b> nome fantasia — mesma razão da BrasilAPI: o cliente informa
   * com frequência o nome pelo qual a empresa opera, e reprovar isso seria falso positivo em massa.
   */
  private boolean nameMatches(String informed, BigBoostCompanyResponse.BasicData data) {
    return NameSimilarity.matches(informed, data.officialName(), nameThreshold)
        || NameSimilarity.matches(informed, data.tradeName(), nameThreshold);
  }

  /**
   * Situação cadastral → desfecho. {@code NULA} é NOT_FOUND (o CNPJ nunca existiu validamente);
   * baixada/suspensa/inapta é MISMATCH (existe, mas não está apta — julgamento humano); ausente ou
   * desconhecida é MISMATCH e nunca MATCH, porque campo que a API deixou de mandar não pode ser
   * lido como "está tudo certo" — foi assim que o fail-open nasceu.
   */
  private static BureauResult.Outcome outcomeOf(String taxIdStatus) {
    String status = taxIdStatus == null ? "" : taxIdStatus.trim().toUpperCase();
    if (status.equals("NULA")) {
      return BureauResult.Outcome.NOT_FOUND;
    }
    return status.equals("ATIVA") ? BureauResult.Outcome.MATCH : BureauResult.Outcome.MISMATCH;
  }

  private static CompanyProfile toProfile(BigBoostCompanyResponse.BasicData data) {
    BigBoostCompanyResponse.Activity main = mainActivity(data);
    return new CompanyProfile(
        parseDate(data.foundedDate()),
        main == null ? null : main.code(),
        main == null ? null : main.description(),
        List.of());
  }

  /** O CNAE principal é o marcado como tal; sem marcação, o primeiro da lista. */
  private static BigBoostCompanyResponse.Activity mainActivity(
      BigBoostCompanyResponse.BasicData data) {
    if (data.activities() == null || data.activities().isEmpty()) {
      return null;
    }
    return data.activities().stream()
        .max(Comparator.comparing(a -> Boolean.TRUE.equals(a.main())))
        .orElse(null);
  }

  /** Data ilegível não pode derrubar a avaliação: vira ausente, e a regra de idade não dispara. */
  private static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
    } catch (DateTimeParseException e) {
      log.warn("Data de abertura ilegível vinda da BigBoost: {}", value);
      return null;
    }
  }

  @Override
  public String name() {
    return "bigboost-cnpj";
  }

  /** Corpo do {@code POST /empresas}; mesmo formato do dataset de pessoas. */
  private record CompanyRequest(
      @JsonProperty("Datasets") String datasets,
      @JsonProperty("q") String q,
      @JsonProperty("Limit") int limit) {}
}
