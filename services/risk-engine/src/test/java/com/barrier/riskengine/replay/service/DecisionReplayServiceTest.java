package com.barrier.riskengine.replay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;
import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import com.barrier.riskengine.assessment.service.AssessmentService;
import com.barrier.riskengine.replay.domain.DecisionNotReplayableException;
import com.barrier.riskengine.replay.domain.DecisionReplay;
import com.barrier.riskengine.replay.domain.GapKind;
import com.barrier.riskengine.replay.domain.ReconstructionGap;
import com.barrier.riskengine.replay.domain.ReplayMode;
import com.barrier.riskengine.replay.domain.ReplayVerdict;
import com.barrier.riskengine.replay.domain.ReplayedRule;
import com.barrier.riskengine.replay.domain.RuleComparison;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.EvaluatedRule;
import com.barrier.riskengine.risk.domain.model.RiskDecision;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.domain.model.RuleOutcome;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import com.barrier.riskengine.risk.service.RiskScoreQueryService;
import com.barrier.riskengine.risk.service.RiskScoringService;
import com.barrier.riskengine.screening.service.ScreeningQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DecisionReplayServiceTest {

  private static final String TENANT = "default";
  private static final AssessmentId ID = AssessmentId.newId();

  @Mock AssessmentService assessments;
  @Mock RiskScoreQueryService riskScores;
  @Mock ScreeningQueryService screenings;
  @Mock ReplayContextRebuilder rebuilder;
  @Mock RiskScoringService scoringService;

  private DecisionReplayService service;

  /** Duas regras com insumos diferentes: uma de screening (reconstruível) e uma de PJ (não). */
  private static RiskRule regra(String code, ContextInput... requires) {
    return new RiskRule() {
      @Override
      public RiskResult evaluate(RiskContext context) {
        return RiskResult.notApplicable(code);
      }

      @Override
      public String code() {
        return code;
      }

      @Override
      public Set<ContextInput> requires() {
        return Set.of(requires);
      }
    };
  }

  @BeforeEach
  void setUp() {
    service =
        new DecisionReplayService(
            assessments,
            riskScores,
            screenings,
            rebuilder,
            scoringService,
            List.of(regra("PEP", ContextInput.SCREENING), regra("NEW_COMPANY", ContextInput.COMPANY)));
    lenient().when(assessments.get(ID, TENANT)).thenReturn(avaliacao());
    lenient().when(screenings.findById(any())).thenReturn(Optional.empty());
  }

  private static Assessment avaliacao() {
    return Assessment.submit(TENANT, UUID.randomUUID().toString(), DocumentType.CPF, "52998224725", "Fulano");
  }

  private static RiskResult disparou(String code, int score) {
    return new RiskResult(code, score, Severity.MEDIUM, "motivo", List.of("ev"), RiskRecommendation.REVIEW);
  }

  private static RiskScore score(List<RiskResult> triggered, List<EvaluatedRule> evaluated, int total, RiskLevel level, RiskRecommendation rec) {
    return new RiskScore(
        UUID.randomUUID(), ID.asString(), level, total, rec, triggered, evaluated,
        UUID.randomUUID(), UUID.randomUUID(), "barrier-risk-rules/1.7.0", Instant.now());
  }

  /** Decisão íntegra: PEP disparou (300/REVIEW), NEW_COMPANY rodou e passou. */
  private static RiskScore decisaoIntegra() {
    RiskResult pep = disparou("PEP", 300);
    return score(
        List.of(pep),
        List.of(
            EvaluatedRule.triggered("PEP", pep, Map.of("score", "300")),
            EvaluatedRule.passed("NEW_COMPANY", RiskResult.notApplicable("NEW_COMPANY"), Map.of("months", "6"))),
        300,
        RiskLevel.MEDIUM,
        RiskRecommendation.REVIEW);
  }

  private static RebuiltContext semLacuna() {
    return new RebuiltContext(
        new RiskContext(ID.asString(), TENANT, null, null, null, null, null), Set.of(), List.of());
  }

  // ---------- AS_DECIDED ----------

  @Test
  void as_decided_monta_o_dossie_sem_reexecutar_nada() {
    when(riskScores.latestFor(ID.asString())).thenReturn(Optional.of(decisaoIntegra()));

    DecisionReplay replay = service.replay(ID, TENANT, ReplayMode.AS_DECIDED);

    assertThat(replay.verdict()).isEqualTo(ReplayVerdict.REPRODUCED);
    assertThat(replay.replayedDecision()).isNull();
    assertThat(replay.rules())
        .extracting(ReplayedRule::ruleCode, ReplayedRule::comparison)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("PEP", RuleComparison.NOT_COMPARED),
            org.assertj.core.groups.Tuple.tuple("NEW_COMPANY", RuleComparison.NOT_COMPARED));
    // O parâmetro efetivo da época é o que `tenant_risk_config`, sendo mutável, não responderia hoje.
    assertThat(replay.rules())
        .filteredOn(r -> r.ruleCode().equals("NEW_COMPANY"))
        .singleElement()
        .extracting(ReplayedRule::recordedParameters)
        .isEqualTo(Map.of("months", "6"));
    verify(scoringService, never()).evaluate(any());
    verify(rebuilder, never()).rebuild(any(), any());
  }

  @Test
  void trilha_incompleta_degrada_e_declara_a_lacuna() {
    // Anterior à V028: só as regras que dispararam foram gravadas.
    RiskResult pep = disparou("PEP", 300);
    when(riskScores.latestFor(ID.asString()))
        .thenReturn(Optional.of(score(List.of(pep), List.of(), 300, RiskLevel.MEDIUM, RiskRecommendation.REVIEW)));

    DecisionReplay replay = service.replay(ID, TENANT, ReplayMode.AS_DECIDED);

    assertThat(replay.verdict()).isEqualTo(ReplayVerdict.DEGRADED);
    assertThat(replay.gaps()).extracting(ReconstructionGap::kind).containsExactly(GapKind.EVALUATED_TRAIL_ABSENT);
  }

  @Test
  void aritmetica_que_nao_fecha_precede_qualquer_outro_veredito() {
    RiskResult pep = disparou("PEP", 300);
    // Resultados somam 300; a linha diz 50 — e ainda por cima sem trilha completa, que sozinha
    // daria DEGRADED. Trilha inconsistente é o achado mais grave e tem de vencer.
    when(riskScores.latestFor(ID.asString()))
        .thenReturn(Optional.of(score(List.of(pep), List.of(), 50, RiskLevel.LOW, RiskRecommendation.APPROVE)));

    DecisionReplay replay = service.replay(ID, TENANT, ReplayMode.AS_DECIDED);

    assertThat(replay.verdict()).isEqualTo(ReplayVerdict.TRAIL_INCONSISTENT);
    assertThat(replay.arithmetic().consistent()).isFalse();
  }

  // ---------- CURRENT_ENGINE ----------

  @Test
  void current_engine_com_mesmo_desfecho_responde_same_decision() {
    when(riskScores.latestFor(ID.asString())).thenReturn(Optional.of(decisaoIntegra()));
    when(rebuilder.rebuild(any(), any())).thenReturn(semLacuna());
    RiskResult pep = disparou("PEP", 300);
    when(scoringService.evaluate(any()))
        .thenReturn(
            new RiskDecision(
                RiskLevel.MEDIUM, RiskRecommendation.REVIEW, 300, List.of(pep),
                List.of(
                    EvaluatedRule.triggered("PEP", pep, Map.of()),
                    EvaluatedRule.passed("NEW_COMPANY", RiskResult.notApplicable("NEW_COMPANY"), Map.of())),
                "barrier-risk-rules/1.8.0"));

    DecisionReplay replay = service.replay(ID, TENANT, ReplayMode.CURRENT_ENGINE);

    assertThat(replay.verdict()).isEqualTo(ReplayVerdict.SAME_DECISION);
    assertThat(replay.replayedDecision().engineVersion()).isEqualTo("barrier-risk-rules/1.8.0");
    assertThat(replay.rules()).allMatch(r -> r.comparison() == RuleComparison.SAME);
  }

  @Test
  void peso_alterado_aparece_como_score_changed_e_muda_o_veredito() {
    when(riskScores.latestFor(ID.asString())).thenReturn(Optional.of(decisaoIntegra()));
    when(rebuilder.rebuild(any(), any())).thenReturn(semLacuna());
    RiskResult pepHoje = disparou("PEP", 500);
    when(scoringService.evaluate(any()))
        .thenReturn(
            new RiskDecision(
                RiskLevel.HIGH, RiskRecommendation.REVIEW, 500, List.of(pepHoje),
                List.of(
                    EvaluatedRule.triggered("PEP", pepHoje, Map.of()),
                    EvaluatedRule.passed("NEW_COMPANY", RiskResult.notApplicable("NEW_COMPANY"), Map.of())),
                "barrier-risk-rules/1.9.0"));

    DecisionReplay replay = service.replay(ID, TENANT, ReplayMode.CURRENT_ENGINE);

    assertThat(replay.verdict()).isEqualTo(ReplayVerdict.DIFFERENT_DECISION);
    assertThat(replay.rules())
        .filteredOn(r -> r.ruleCode().equals("PEP"))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.comparison()).isEqualTo(RuleComparison.SCORE_CHANGED);
              assertThat(r.recordedScore()).isEqualTo(300);
              assertThat(r.replayedScore()).isEqualTo(500);
            });
  }

  @Test
  void regra_desligada_no_registry_aparece_como_outcome_changed() {
    when(riskScores.latestFor(ID.asString())).thenReturn(Optional.of(decisaoIntegra()));
    when(rebuilder.rebuild(any(), any())).thenReturn(semLacuna());
    when(scoringService.evaluate(any()))
        .thenReturn(
            new RiskDecision(
                RiskLevel.LOW, RiskRecommendation.APPROVE, 0, List.of(),
                List.of(
                    EvaluatedRule.suppressed("PEP"),
                    EvaluatedRule.passed("NEW_COMPANY", RiskResult.notApplicable("NEW_COMPANY"), Map.of())),
                "barrier-risk-rules/1.8.0"));

    DecisionReplay replay = service.replay(ID, TENANT, ReplayMode.CURRENT_ENGINE);

    assertThat(replay.verdict()).isEqualTo(ReplayVerdict.DIFFERENT_DECISION);
    assertThat(replay.rules())
        .filteredOn(r -> r.ruleCode().equals("PEP"))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.comparison()).isEqualTo(RuleComparison.OUTCOME_CHANGED);
              assertThat(r.recordedOutcome()).isEqualTo(RuleOutcome.TRIGGERED);
              assertThat(r.replayedOutcome()).isEqualTo(RuleOutcome.SUPPRESSED);
            });
  }

  @Test
  void regra_sem_insumo_reconstruido_nao_e_reportada_como_tendo_passado() {
    when(riskScores.latestFor(ID.asString())).thenReturn(Optional.of(decisaoIntegra()));
    when(rebuilder.rebuild(any(), any()))
        .thenReturn(
            new RebuiltContext(
                new RiskContext(ID.asString(), TENANT, null, null, null, null, null),
                Set.of(ContextInput.COMPANY),
                List.of(
                    ReconstructionGap.of(
                        GapKind.COMPANY_NOT_PERSISTED, ContextInput.COMPANY, "transiente"))));
    RiskResult pep = disparou("PEP", 300);
    when(scoringService.evaluate(any()))
        .thenReturn(
            new RiskDecision(
                RiskLevel.MEDIUM, RiskRecommendation.REVIEW, 300, List.of(pep),
                List.of(
                    EvaluatedRule.triggered("PEP", pep, Map.of()),
                    // Sem o CompanyProfile a regra devolve "não disparou". É exatamente este
                    // resultado que NÃO pode virar "rodou e passou" na resposta.
                    EvaluatedRule.passed("NEW_COMPANY", RiskResult.notApplicable("NEW_COMPANY"), Map.of())),
                "barrier-risk-rules/1.8.0"));

    DecisionReplay replay = service.replay(ID, TENANT, ReplayMode.CURRENT_ENGINE);

    assertThat(replay.verdict()).isEqualTo(ReplayVerdict.DEGRADED);
    assertThat(replay.rules())
        .filteredOn(r -> r.ruleCode().equals("NEW_COMPANY"))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.comparison()).isEqualTo(RuleComparison.NOT_REPLAYABLE);
              assertThat(r.missingInputs()).containsExactly(ContextInput.COMPANY);
              assertThat(r.replayedOutcome()).as("resultado sobre insumo ausente não sai").isNull();
              assertThat(r.replayedScore()).isNull();
            });
    // A regra cujo insumo é reconstruível continua comparável — a degradação é por regra, não global.
    assertThat(replay.rules())
        .filteredOn(r -> r.ruleCode().equals("PEP"))
        .singleElement()
        .extracting(ReplayedRule::comparison)
        .isEqualTo(RuleComparison.SAME);
  }

  @Test
  void avaliacao_sem_decisao_do_motor_nao_e_replayavel() {
    when(riskScores.latestFor(ID.asString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.replay(ID, TENANT, ReplayMode.AS_DECIDED))
        .isInstanceOf(DecisionNotReplayableException.class);
  }
}
