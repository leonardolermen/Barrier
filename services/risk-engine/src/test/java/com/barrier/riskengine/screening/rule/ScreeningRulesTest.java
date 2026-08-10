package com.barrier.riskengine.screening.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.MatchBasis;
import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreenedParty;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScreeningRulesTest {

  private static final WatchlistQuery QUERY = new WatchlistQuery("CPF", "11144477735", "Fulano");
  private static final ScreenedParty TITULAR = QUERY.party();

  private ScreeningContext contextWith(WatchlistEntry... entries) {
    return new ScreeningContext(QUERY, List.of(entries));
  }

  @Test
  void pepRuleSoPegaRegistrosPep() {
    var context =
        contextWith(
            new WatchlistEntry(MatchType.PEP, MatchBasis.NAME, TITULAR, "base-pep", "F", "cargo"),
            new WatchlistEntry(MatchType.SANCTION, MatchBasis.DOCUMENT, TITULAR, "OFAC", "F", "sdn"));

    var hits = new PepMatchRule().evaluate(context);

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).type()).isEqualTo(MatchType.PEP);
  }

  @Test
  void sanctionRuleSoPegaRegistrosDeSancao() {
    var context =
        contextWith(
            new WatchlistEntry(MatchType.PEP, MatchBasis.NAME, TITULAR, "base-pep", "F", "cargo"),
            new WatchlistEntry(MatchType.SANCTION, MatchBasis.DOCUMENT, TITULAR, "OFAC", "F", "sdn"));

    var hits = new SanctionMatchRule().evaluate(context);

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).type()).isEqualTo(MatchType.SANCTION);
  }

  @Test
  void semRegistrosNaoGeraApontamento() {
    var hits = new PepMatchRule().evaluate(contextWith());

    assertThat(hits).isEmpty();
  }

  @Test
  void adverseMediaRuleSoPegaRegistrosDeMidiaNegativa() {
    var context =
        contextWith(
            new WatchlistEntry(MatchType.PEP, MatchBasis.NAME, TITULAR, "base-pep", "F", "cargo"),
            new WatchlistEntry(MatchType.ADVERSE_MEDIA, MatchBasis.NAME, TITULAR, "stub-negative-media", "F", "fraude"));

    var hits = new AdverseMediaMatchRule().evaluate(context);

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).type()).isEqualTo(MatchType.ADVERSE_MEDIA);
  }
}
