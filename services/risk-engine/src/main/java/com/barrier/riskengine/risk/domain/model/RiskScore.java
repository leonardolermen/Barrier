package com.barrier.riskengine.risk.domain.model;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Registro persistido de uma pontuação de risco (com a versão do motor, para auditoria).
 *
 * @param evaluated todas as regras avaliadas, com o desfecho de cada uma
 * @param identityCheckId a verificação de identidade <b>exata</b> que alimentou esta decisão
 * @param screeningResultId o screening <b>exato</b> que alimentou esta decisão
 */
public record RiskScore(
    UUID id,
    String assessmentId,
    RiskLevel level,
    int totalScore,
    RiskRecommendation recommendation,
    List<RiskResult> results,
    List<EvaluatedRule> evaluated,
    UUID identityCheckId,
    UUID screeningResultId,
    String engineVersion,
    Instant scoredAt) {

  public RiskScore {
    results = List.copyOf(results);
    evaluated = evaluated == null ? List.of() : List.copyOf(evaluated);
  }

  /**
   * Monta o registro a partir do contexto que produziu a decisão.
   *
   * <p>Guardar os ids da identidade e do screening resolve uma ambiguidade real da trilha: uma
   * avaliação que falhou e foi retentada deixa <b>várias</b> linhas de {@code identity_checks} e
   * {@code screening_results} com o mesmo {@code assessment_id}, e nada dizia qual delas produziu a
   * decisão gravada. O auditor via N respostas de bureau e nenhuma indicação de qual valeu.
   */
  public static RiskScore from(RiskContext context, RiskDecision decision) {
    return new RiskScore(
        UUID.randomUUID(),
        context.assessmentId(),
        decision.level(),
        decision.totalScore(),
        decision.recommendation(),
        decision.results(),
        decision.evaluated(),
        context.identity() == null ? null : context.identity().id(),
        context.screening() == null ? null : context.screening().id(),
        decision.engineVersion(),
        Instant.now());
  }
}
