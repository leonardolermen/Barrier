package com.barrier.riskengine.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.identity.domain.CompanyProfile;
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
import com.barrier.riskengine.screening.domain.MatchBasis;
import com.barrier.riskengine.screening.domain.MatchType;
import com.barrier.riskengine.screening.domain.ScreeningHit;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.time.LocalDate;
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
        null);
  }

  /** PJ com sócio estrangeiro: dispara CORPORATE_STRUCTURE (regra de apetite, desligável). */
  private RiskContext contextComSocioEstrangeiro() {
    CompanyProfile company =
        new CompanyProfile(
            LocalDate.of(2015, 1, 1),
            "6201500",
            "Desenvolvimento de software",
            List.of(new CompanyProfile.Partner("JOHN DOE", false, true, "Sócio-Administrador")));
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "brasilapi", "d"),
        ScreeningResult.of("aid", List.of()),
        company,
        null);
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
  void sancaoPorDocumentoBloqueiaComScoreMaximo() {
    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(MatchType.SANCTION, MatchBasis.DOCUMENT, "OFAC", "X", "sdn")));

    assertThat(d.totalScore()).isEqualTo(1000);
    assertThat(d.level()).isEqualTo(RiskLevel.CRITICAL);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REJECT);
  }

  /**
   * Regressão: match fuzzy de nome contra a SDN reprovava automaticamente. Um homônimo de
   * sancionado tem de ir para análise humana, não ser recusado sem recurso.
   */
  @Test
  void sancaoApenasPorNomeVaiParaRevisaoNaoReprova() {
    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(MatchType.SANCTION, MatchBasis.NAME, "OFAC", "X", "sdn")));

    assertThat(d.totalScore()).isEqualTo(500);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(d.results().getFirst().ruleCode()).isEqualTo("SANCTION_NAME_MATCH");
  }

  /** Documento prevalece: já há identificação inequívoca, o indício por nome não a enfraquece. */
  @Test
  void sancaoPorDocumentoPrevaleceSobreMatchPorNome() {
    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(MatchType.SANCTION, MatchBasis.NAME, "OFAC", "X", "aka"),
                new ScreeningHit(MatchType.SANCTION, MatchBasis.DOCUMENT, "OFAC", "X", "sdn")));

    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REJECT);
  }

  @Test
  void pepForcaRevisao() {
    RiskDecision d =
        service.score(
            context(IdentityStatus.VERIFIED, new ScreeningHit(MatchType.PEP, MatchBasis.NAME, "base", "X", "cargo")));

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

  /**
   * O kill switch continua valendo para regras de <b>apetite de risco</b> — este é o caso de uso
   * legítimo do registry: desarmar uma regra que passou a gerar falso positivo em massa, sem
   * esperar deploy.
   */
  @Test
  void regraDeApetiteDesabilitadaNoRegistryNaoContribuiParaOScore() {
    when(registryService.isActive("CORPORATE_STRUCTURE")).thenReturn(false);

    RiskDecision d = service.score(contextComSocioEstrangeiro());

    assertThat(d.totalScore()).isZero();
    assertThat(d.level()).isEqualTo(RiskLevel.LOW);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.APPROVE);
    assertThat(d.results()).isEmpty();
  }

  /**
   * Regressão do achado mais explorável da auditoria: um {@code PUT /v1/risk-rules/SANCTION
   * {enabled:false}} zerava o screening de sanções para todos os tenants. Mesmo que o registry
   * responda "inativa" (linha escrita direto no banco, ou por uma versão anterior da API), o motor
   * tem de executar a regra.
   */
  @Test
  void regraRegulatoriaDesabilitadaNoRegistryAindaAssimBloqueia() {
    when(registryService.isActive("SANCTION")).thenReturn(false);

    RiskDecision d =
        service.score(
            context(
                IdentityStatus.VERIFIED,
                new ScreeningHit(MatchType.SANCTION, MatchBasis.DOCUMENT, "OFAC", "X", "sdn")));

    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REJECT);
    assertThat(d.level()).isEqualTo(RiskLevel.CRITICAL);
  }

  /**
   * Regressão do fail-open mais grave da auditoria: com bureau indisponível a identidade não foi
   * confirmada por nenhum provider, então o motor não pode aprovar — mesmo que o score (150) caia
   * na banda LOW. A recomendação da regra tem que sobrepor a banda.
   */
  @Test
  void bureauIndisponivelNaoAprova() {
    RiskDecision d = service.score(context(IdentityStatus.UNAVAILABLE));

    assertThat(d.totalScore()).isEqualTo(150);
    assertThat(d.level()).isEqualTo(RiskLevel.LOW);
    assertThat(d.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(d.results()).hasSize(1);
    assertThat(d.results().getFirst().ruleCode()).isEqualTo("IDENTITY_UNAVAILABLE");
  }
}
