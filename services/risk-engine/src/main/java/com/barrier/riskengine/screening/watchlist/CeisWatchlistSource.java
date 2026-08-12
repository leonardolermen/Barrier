package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * CEIS — Cadastro de Empresas Inidôneas e Suspensas (CGU). Habilitado por
 * {@code barrier.watchlist.cgu.enabled=true} (desligado por padrão: em testes/dev não baixa nada).
 */
@Component
@ConditionalOnProperty("barrier.watchlist.cgu.enabled")
class CeisWatchlistSource extends CguWatchlistSource {

  private final String referenceDate;

  CeisWatchlistSource(
      @Qualifier("cguRestClient") RestClient client,
      @Value("${barrier.watchlist.cgu.reference-date:}") String referenceDate) {
    super(client);
    this.referenceDate =
        referenceDate.isBlank()
            ? LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            : referenceDate;
  }

  /**
   * Inidoneidade em licitação, não sanção financeira: a empresa segue legalmente apta a manter
   * relacionamento bancário. Classificada como SANCTION, gerava recusa automática — negação de
   * serviço a quem a lei não impede de ser cliente.
   */
  @Override
  protected MatchType matchType() {
    return MatchType.DEBARMENT;
  }

  @Override
  public String source() {
    return "CEIS";
  }

  @Override
  protected String pathSegment() {
    return "ceis";
  }

  @Override
  protected String referenceDate() {
    return referenceDate;
  }
}
