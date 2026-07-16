package com.barrier.riskengine.risk.registry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class RiskRuleRegistryEntryTest {

  private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

  @Test
  void desabilitadaNuncaEstaAtiva() {
    var entry =
        new RiskRuleRegistryEntry(
            "NEW_COMPANY", "desc", RiskRuleCriticality.ALERT, false, null, null, NOW);

    assertThat(entry.activeAt(NOW)).isFalse();
  }

  @Test
  void semVigenciaEHabilitadaEstaAtivaSempre() {
    var entry =
        new RiskRuleRegistryEntry(
            "NEW_COMPANY", "desc", RiskRuleCriticality.ALERT, true, null, null, NOW);

    assertThat(entry.activeAt(NOW)).isTrue();
    assertThat(entry.activeAt(NOW.plus(1000, ChronoUnit.DAYS))).isTrue();
  }

  @Test
  void antesDoValidFromNaoEstaAtiva() {
    var entry =
        new RiskRuleRegistryEntry(
            "NEW_COMPANY",
            "desc",
            RiskRuleCriticality.ALERT,
            true,
            NOW.plus(1, ChronoUnit.DAYS),
            null,
            NOW);

    assertThat(entry.activeAt(NOW)).isFalse();
    assertThat(entry.activeAt(NOW.plus(2, ChronoUnit.DAYS))).isTrue();
  }

  @Test
  void depoisDoValidUntilNaoEstaAtiva() {
    var entry =
        new RiskRuleRegistryEntry(
            "NEW_COMPANY",
            "desc",
            RiskRuleCriticality.ALERT,
            true,
            null,
            NOW.minus(1, ChronoUnit.DAYS),
            NOW);

    assertThat(entry.activeAt(NOW)).isFalse();
    assertThat(entry.activeAt(NOW.minus(2, ChronoUnit.DAYS))).isTrue();
  }
}
