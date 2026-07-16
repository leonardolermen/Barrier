package com.barrier.riskengine.risk.registry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.risk.registry.domain.RiskRuleCriticality;
import com.barrier.riskengine.risk.registry.domain.RiskRuleRegistryEntry;
import com.barrier.riskengine.risk.registry.repository.RiskRuleRegistryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskRuleRegistryServiceImplTest {

  private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock RiskRuleRegistryRepository repository;

  private RiskRuleRegistryServiceImpl service() {
    return new RiskRuleRegistryServiceImpl(repository, FIXED);
  }

  @Test
  void semLinhaNoRegistryEAtivaPorPadrao() {
    when(repository.findByRuleCode("UNKNOWN_RULE")).thenReturn(Optional.empty());

    assertThat(service().isActive("UNKNOWN_RULE")).isTrue();
  }

  @Test
  void comLinhaDesabilitadaNaoEstaAtiva() {
    when(repository.findByRuleCode("NEW_COMPANY"))
        .thenReturn(
            Optional.of(
                new RiskRuleRegistryEntry(
                    "NEW_COMPANY", "desc", RiskRuleCriticality.ALERT, false, null, null, NOW)));

    assertThat(service().isActive("NEW_COMPANY")).isFalse();
  }

  @Test
  void comLinhaHabilitadaDentroDaVigenciaEstaAtiva() {
    when(repository.findByRuleCode("NEW_COMPANY"))
        .thenReturn(
            Optional.of(
                new RiskRuleRegistryEntry(
                    "NEW_COMPANY", "desc", RiskRuleCriticality.ALERT, true, null, null, NOW)));

    assertThat(service().isActive("NEW_COMPANY")).isTrue();
  }
}
