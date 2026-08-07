package com.barrier.riskengine.screening.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistProvider;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import com.barrier.riskengine.screening.domain.MatchBasis;
import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.screening.domain.ScreeningStatus;
import com.barrier.riskengine.screening.repository.ScreeningResultRepository;
import com.barrier.riskengine.screening.rule.PepMatchRule;
import com.barrier.riskengine.screening.rule.SanctionMatchRule;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScreeningServiceTest {

  @Mock WatchlistProvider provider;
  @Mock ScreeningResultRepository repository;

  private ScreeningService service;

  @BeforeEach
  void setUp() {
    when(repository.save(any(ScreeningResult.class))).thenAnswer(inv -> inv.getArgument(0));
    service =
        new ScreeningService(
            List.of(provider), List.of(new PepMatchRule(), new SanctionMatchRule()), repository);
  }

  private ScreeningCommand command() {
    return new ScreeningCommand("aid", "CPF", "11144477735", "Fulano");
  }

  @Test
  void semRegistrosResultaClear() {
    when(provider.search(any(WatchlistQuery.class))).thenReturn(List.of());

    ScreeningResult result = service.screen(command());

    assertThat(result.status()).isEqualTo(ScreeningStatus.CLEAR);
    assertThat(result.hits()).isEmpty();
  }

  @Test
  void registroDeSancaoResultaHit() {
    when(provider.search(any(WatchlistQuery.class)))
        .thenReturn(
            List.of(new WatchlistEntry(MatchType.SANCTION, MatchBasis.DOCUMENT, "OFAC", "Empresa X", "SDN")));

    ScreeningResult result = service.screen(command());

    assertThat(result.status()).isEqualTo(ScreeningStatus.HIT);
    assertThat(result.hits()).hasSize(1);
    assertThat(result.hits().get(0).type()).isEqualTo(MatchType.SANCTION);
  }

  @Test
  void pepESancaoGeramDoisApontamentos() {
    when(provider.search(any(WatchlistQuery.class)))
        .thenReturn(
            List.of(
                new WatchlistEntry(MatchType.PEP, MatchBasis.NAME, "base-pep", "Fulano", "cargo"),
                new WatchlistEntry(MatchType.SANCTION, MatchBasis.DOCUMENT, "ONU", "Fulano", "lista")));

    ScreeningResult result = service.screen(command());

    assertThat(result.hits()).hasSize(2);
    assertThat(result.hasHits()).isTrue();
  }
}
