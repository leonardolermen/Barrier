package com.barrier.riskengine.replay.domain;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.domain.model.ScoreAggregation;
import java.util.List;

/**
 * Reconferência da aritmética da decisão: recalcula soma, banda e recomendação a partir dos
 * resultados de regra <b>persistidos</b> e compara com o que {@code risk_scores} gravou.
 *
 * <p>É verificação de integridade de verdade, e não depende de reconstruir insumo nenhum — só do que
 * está na própria linha. Divergência aqui significa uma de três coisas, todas graves: a linha foi
 * adulterada, uma migration corrompeu uma coluna, ou o motor gravou desfecho que suas próprias
 * regras não produzem.
 *
 * <p>Usa {@link ScoreAggregation}, a <b>mesma</b> função que o motor usa para decidir. Uma cópia
 * própria da regra de agregação não conferiria nada: as duas divergiriam com o tempo, e a
 * divergência apareceria como "a trilha está íntegra".
 */
public record ArithmeticCheck(
    boolean consistent,
    int recordedScore,
    int recomputedScore,
    RiskLevel recordedLevel,
    RiskLevel recomputedLevel,
    RiskRecommendation recordedRecommendation,
    RiskRecommendation recomputedRecommendation) {

  public static ArithmeticCheck of(RiskScore score) {
    List<RiskResult> triggered = score.results();
    ScoreAggregation recomputed = ScoreAggregation.of(triggered);
    boolean consistent =
        recomputed.totalScore() == score.totalScore()
            && recomputed.level() == score.level()
            && recomputed.recommendation() == score.recommendation();
    return new ArithmeticCheck(
        consistent,
        score.totalScore(),
        recomputed.totalScore(),
        score.level(),
        recomputed.level(),
        score.recommendation(),
        recomputed.recommendation());
  }
}
