package com.barrier.riskengine.replay.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A reconferência da aritmética precisa <b>acusar</b> divergência — senão ela é enfeite, e o replay
 * devolveria "trilha íntegra" para qualquer linha, inclusive uma adulterada.
 */
class ArithmeticCheckTest {

  private static RiskResult resultado(String code, int score, RiskRecommendation recomendacao) {
    return new RiskResult(code, score, Severity.MEDIUM, "motivo", List.of("evidência"), recomendacao);
  }

  private static RiskScore gravado(
      List<RiskResult> results, int totalScore, RiskLevel level, RiskRecommendation recomendacao) {
    return new RiskScore(
        UUID.randomUUID(),
        "aid",
        level,
        totalScore,
        recomendacao,
        results,
        List.of(),
        null,
        null,
        "barrier-risk-rules/1.8.0",
        Instant.now());
  }

  @Test
  void decisao_integra_confere() {
    // PEP (300, REVIEW) → total 300, banda MEDIUM, mas o override da regra manda REVIEW.
    RiskScore score =
        gravado(
            List.of(resultado("PEP", 300, RiskRecommendation.REVIEW)),
            300,
            RiskLevel.MEDIUM,
            RiskRecommendation.REVIEW);

    ArithmeticCheck check = ArithmeticCheck.of(score);

    assertThat(check.consistent()).isTrue();
    assertThat(check.recomputedScore()).isEqualTo(300);
    assertThat(check.recomputedLevel()).isEqualTo(RiskLevel.MEDIUM);
    assertThat(check.recomputedRecommendation()).isEqualTo(RiskRecommendation.REVIEW);
  }

  @Test
  void score_adulterado_acusa_divergencia() {
    // Os resultados somam 300; a linha diz 50. É o que uma alteração no banco pareceria.
    RiskScore score =
        gravado(
            List.of(resultado("PEP", 300, RiskRecommendation.REVIEW)),
            50,
            RiskLevel.LOW,
            RiskRecommendation.APPROVE);

    ArithmeticCheck check = ArithmeticCheck.of(score);

    assertThat(check.consistent()).isFalse();
    assertThat(check.recordedScore()).isEqualTo(50);
    assertThat(check.recomputedScore()).isEqualTo(300);
  }

  @Test
  void recomendacao_adulterada_acusa_divergencia_mesmo_com_score_certo() {
    // O score bate, o desfecho não: uma aprovação gravada sobre um apontamento que pede revisão é
    // exatamente a adulteração que interessa detectar, e ela não move o total.
    RiskScore score =
        gravado(
            List.of(resultado("PEP", 300, RiskRecommendation.REVIEW)),
            300,
            RiskLevel.MEDIUM,
            RiskRecommendation.APPROVE);

    ArithmeticCheck check = ArithmeticCheck.of(score);

    assertThat(check.consistent()).isFalse();
    assertThat(check.recordedRecommendation()).isEqualTo(RiskRecommendation.APPROVE);
    assertThat(check.recomputedRecommendation()).isEqualTo(RiskRecommendation.REVIEW);
  }

  @Test
  void banda_nao_reprova_sozinha_tambem_na_reconferencia() {
    // PEP (300, REVIEW) + SANCTION_NAME_MATCH (500, REVIEW) = 800 → CRITICAL. A banda agrava até
    // REVIEW e não além: a reconferência tem de reproduzir essa decisão do motor, não a aritmética
    // ingênua. Se ela tivesse cópia própria da regra, aqui apareceria REJECT e uma decisão correta
    // seria reportada como trilha inconsistente.
    RiskScore score =
        gravado(
            List.of(
                resultado("PEP", 300, RiskRecommendation.REVIEW),
                resultado("SANCTION_NAME_MATCH", 500, RiskRecommendation.REVIEW)),
            800,
            RiskLevel.CRITICAL,
            RiskRecommendation.REVIEW);

    ArithmeticCheck check = ArithmeticCheck.of(score);

    assertThat(check.consistent()).isTrue();
    assertThat(check.recomputedRecommendation()).isEqualTo(RiskRecommendation.REVIEW);
  }
}
