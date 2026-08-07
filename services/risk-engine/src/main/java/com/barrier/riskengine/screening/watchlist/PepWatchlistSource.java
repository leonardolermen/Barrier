package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.MatchType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PEP — Pessoas Expostas Politicamente, cadastro publicado pela CGU no Portal da Transparência.
 *
 * <p>Fecha a lacuna mais séria de PLD-FT do motor: até aqui <b>nenhuma</b> fonte produzia
 * {@link MatchType#PEP}. CEIS, CNEP e OFAC são todas de sanção, então a {@code PepRiskRule} — que
 * cita a Circular BCB 3.978 e existe para forçar due diligence reforçada — nunca disparava em
 * produção. A única linha PEP do sistema estava no CSV de exemplo, justamente a fonte que o
 * {@link WatchlistReadinessGuard} proíbe como única em produção.
 *
 * <p><b>Particularidade do dataset:</b> por exigência de privacidade, a CGU publica o CPF
 * mascarado ({@code ***.123.456-**}). Não dá para fazer match exato por documento; o match é por
 * nome, com os 6 dígitos centrais servindo de discriminador (ver {@link MaskedCpf} e
 * {@code FuzzyNameWatchlistProvider}). O desfecho é REVIEW, nunca reprovação automática — ser PEP
 * não é impedimento, é gatilho de diligência.
 *
 * <p>Os rótulos de coluna são resolvidos pelo cabeçalho, com alternativas: o dataset já mudou de
 * nomenclatura entre publicações e a lista não pode quebrar em silêncio por causa disso.
 */
@Component
@ConditionalOnProperty("barrier.watchlist.cgu.enabled")
class PepWatchlistSource extends CguWatchlistSource {

  private final String referenceDate;

  PepWatchlistSource(
      @Qualifier("cguRestClient") RestClient client,
      @Value("${barrier.watchlist.cgu.reference-date:}") String referenceDate) {
    super(client);
    this.referenceDate =
        referenceDate.isBlank()
            ? LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            : referenceDate;
  }

  @Override
  public String source() {
    return "PEP";
  }

  @Override
  protected String pathSegment() {
    return "pep";
  }

  @Override
  protected String referenceDate() {
    return referenceDate;
  }

  @Override
  protected MatchType matchType() {
    return MatchType.PEP;
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Predicate<String>[] documentColumns() {
    return new Predicate[] {
      (Predicate<String>) h -> h.equals("CPF"), (Predicate<String>) h -> h.contains("CPF")
    };
  }

  @Override
  @SuppressWarnings("unchecked")
  protected Predicate<String>[] nameColumns() {
    return new Predicate[] {
      (Predicate<String>) h -> h.contains("NOME PEP"),
      (Predicate<String>) h -> h.contains("NOME DO PEP"),
      (Predicate<String>) h -> h.contains("NOME")
    };
  }

  /** O cargo é o que o analista precisa para julgar a exposição — é o detalhe mais útil aqui. */
  @Override
  @SuppressWarnings("unchecked")
  protected Predicate<String>[] detailColumns() {
    return new Predicate[] {
      (Predicate<String>) h -> h.contains("DESCRICAO FUNCAO"),
      (Predicate<String>) h -> h.contains("DESCRICAO DA FUNCAO"),
      (Predicate<String>) h -> h.contains("FUNCAO"),
      (Predicate<String>) h -> h.contains("ORGAO")
    };
  }
}
