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
 * CNEP — Cadastro Nacional de Empresas Punidas (CGU, Lei Anticorrupção). Habilitado por
 * {@code barrier.watchlist.cgu.enabled=true}.
 */
@Component
@ConditionalOnProperty("barrier.watchlist.cgu.enabled")
class CnepWatchlistSource extends CguWatchlistSource {

  private final String referenceDate;

  CnepWatchlistSource(
      @Qualifier("cguRestClient") RestClient client,
      @Value("${barrier.watchlist.cgu.reference-date:}") String referenceDate) {
    super(client);
    this.referenceDate =
        referenceDate.isBlank()
            ? LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            : referenceDate;
  }

  /**
   * Punição pela Lei Anticorrupção é impedimento perante a administração pública, não sanção
   * financeira: não bloqueia relacionamento bancário. Ver {@link MatchType#DEBARMENT}.
   */
  @Override
  protected MatchType matchType() {
    return MatchType.DEBARMENT;
  }

  @Override
  public String source() {
    return "CNEP";
  }

  @Override
  protected String pathSegment() {
    return "cnep";
  }

  @Override
  protected String referenceDate() {
    return referenceDate;
  }
}
