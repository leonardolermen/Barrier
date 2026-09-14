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
 * @param policyVersion versão da política custom do tenant que contribuiu regras a esta decisão;
 *     {@code null} quando o tenant não tem política ativa — segundo eixo de versão, ao lado de
 *     {@code engineVersion}
 * @param scoredAt o {@code referenceInstant} do {@code RiskContext} que produziu esta decisão —
 *     <b>não</b> o instante em que esta linha foi persistida. Os dois divergem pela duração do
 *     round-trip de bureau/screening, que corre entre a captura do instante e a gravação; gravar
 *     {@code Instant.now()} aqui faria o replay usar um "agora" diferente do que a decisão
 *     original viu, e uma decisão avaliada perto da virada do dia UTC replayaria contra outra
 *     {@code LocalDate} — uma regra de janela de data podia virar, e apareceria como diferença de
 *     <b>motor</b> no dossiê, quando o motor nunca mudou.
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
    Integer policyVersion,
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
   *
   * <p>{@code scoredAt} vem de {@code context.referenceInstant()}, não de {@code Instant.now()}: é
   * o instante capturado pelo {@code AssessmentProcessor} <b>antes</b> dos round-trips de bureau, o
   * mesmo que a política usou nos operadores {@code OLDER_THAN}/{@code WITHIN_LAST}. Gravar o
   * instante de persistência em vez disso divergiria do que a decisão realmente viu, e o {@code
   * ReplayContextRebuilder} devolveria esse valor errado como se fosse o instante da decisão (ver
   * §5.6 do desenho).
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
        decision.policyVersion(),
        context.referenceInstant());
  }
}
