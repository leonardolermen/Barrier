package com.barrier.riskengine.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.registry.service.RiskRuleRegistryService;
import com.barrier.riskengine.risk.repository.RiskScoreRepository;
import com.barrier.riskengine.risk.rule.CorporateStructureRiskRule;
import com.barrier.riskengine.risk.rule.IdentityRiskRule;
import com.barrier.riskengine.risk.rule.PepRiskRule;
import com.barrier.riskengine.risk.rule.RiskContext;
import com.barrier.riskengine.risk.rule.RiskRule;
import com.barrier.riskengine.risk.rule.SanctionRiskRule;
import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskScoringServiceTest {

  @Mock RiskScoreRepository repository;
  @Mock RiskRuleRegistryService registryService;

  private RiskScoringService service;
  private List<RiskRule> configuredRules;

  @BeforeEach
  void setUp() {
    when(repository.save(any(RiskScore.class))).thenAnswer(inv -> inv.getArgument(0));
    when(registryService.isActive(anyString())).thenReturn(true);
    configuredRules =
        List.of(
            new IdentityRiskRule(),
            new SanctionRiskRule(),
            new PepRiskRule(),
            new CorporateStructureRiskRule());
    service = new RiskScoringService(configuredRules, repository, registryService);
  }

  private RiskContext context(IdentityStatus identity, ScreeningHit... hits) {
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", identity, "stub", "d"),
        ScreeningResult.of("aid", List.of(hits)),
        null,
        null,
        null,
        0,
        0,
        "CPF",
        "11144477735",
        List.of());
  }

  @Test
  void verificadoSemApontamentoAprovaComScoreZero() {
    RiskDecision d = service.score(context(IdentityStatus.VERIFIED));

    assertThat(d.totalScore()).isZero();
    assertThat(d.level()).isEqualTo(RiskLevel.LOW);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.APPROVE);
    assertThat(d.results()).isEmpty();
    assertThat(d.engineVersion()).isEqualTo(RiskScoringService.ENGINE_VERSION);
  }

  @Test
  void sancaoBloqueiaComScoreMaximo() {
    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED, new ScreeningHit(MatchType.SANCTION, "OFAC", "X", "sdn")));

    assertThat(d.totalScore()).isEqualTo(1000);
    assertThat(d.level()).isEqualTo(RiskLevel.CRITICAL);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REJECT);
  }

  @Test
  void pepForcaRevisao() {
    RiskDecision d =
        service.score(
            context(IdentityStatus.VERIFIED, new ScreeningHit(MatchType.PEP, "base", "X", "cargo")));

    assertThat(d.totalScore()).isEqualTo(300);
    assertThat(d.level()).isEqualTo(RiskLevel.MEDIUM);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
  }

  @Test
  void identidadeNaoEncontradaBloqueia() {
    RiskDecision d = service.score(context(IdentityStatus.NOT_FOUND));

    assertThat(d.totalScore()).isEqualTo(900);
    assertThat(d.level()).isEqualTo(RiskLevel.CRITICAL);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REJECT);
  }

  @Test
  void regraDesabilitadaNoRegistryNaoContribuiParaOScore() {
    when(registryService.isActive("SANCTION")).thenReturn(false);

    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED, new ScreeningHit(MatchType.SANCTION, "OFAC", "X", "sdn")));

    assertThat(d.totalScore()).isZero();
    assertThat(d.level()).isEqualTo(RiskLevel.LOW);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.APPROVE);
    assertThat(d.results()).isEmpty();
  }

  @Test
  void bureauIndisponivelPontuaMasNaoBloqueia() {
    RiskDecision d = service.score(context(IdentityStatus.UNAVAILABLE));

    assertThat(d.totalScore()).isEqualTo(150);
    assertThat(d.level()).isEqualTo(RiskLevel.LOW);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.APPROVE);
    assertThat(d.results()).hasSize(1);
  }
}
