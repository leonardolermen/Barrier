package com.barrier.riskengine.replay.domain;

import com.barrier.riskengine.risk.domain.model.RuleOutcome;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import java.util.Map;
import java.util.Set;

/**
 * Uma regra, do jeito que ela apareceu na decisão gravada e do jeito que ela responde hoje.
 *
 * @param ruleCode família da regra ({@code RiskRule.code()})
 * @param recordedOutcome o que aconteceu com ela na decisão; {@code null} quando a regra não existia
 *     então ({@link RuleComparison#ADDED})
 * @param recordedScore pontos que ela somou na decisão; {@code null} se não rodou ou não existia
 * @param recordedReason motivo registrado; {@code null} quando não houve execução
 * @param recordedParameters parâmetros efetivos da época — a metade da reprodutibilidade que
 *     {@code tenant_risk_config}, sendo mutável, não conseguiria responder hoje
 * @param replayedOutcome o que ela responde agora; {@code null} em {@link ReplayMode#AS_DECIDED},
 *     e também quando a comparação é {@link RuleComparison#NOT_REPLAYABLE} ou
 *     {@link RuleComparison#REMOVED}
 * @param replayedScore pontos que ela somaria agora; mesmas condições de nulidade
 * @param replayedReason motivo agora; mesmas condições de nulidade
 * @param comparison o veredito desta regra
 * @param missingInputs insumos declarados por ela que não foram reconstruídos — vazio exceto em
 *     {@link RuleComparison#NOT_REPLAYABLE}, e é o que diz <b>por que</b> ela não pôde ser reexecutada
 */
public record ReplayedRule(
    String ruleCode,
    RuleOutcome recordedOutcome,
    Integer recordedScore,
    String recordedReason,
    Map<String, String> recordedParameters,
    RuleOutcome replayedOutcome,
    Integer replayedScore,
    String replayedReason,
    RuleComparison comparison,
    Set<ContextInput> missingInputs) {

  public ReplayedRule {
    recordedParameters = recordedParameters == null ? Map.of() : Map.copyOf(recordedParameters);
    missingInputs = missingInputs == null ? Set.of() : Set.copyOf(missingInputs);
  }

  public boolean replayable() {
    return comparison != RuleComparison.NOT_REPLAYABLE;
  }

  public boolean differs() {
    return comparison == RuleComparison.SCORE_CHANGED
        || comparison == RuleComparison.OUTCOME_CHANGED
        || comparison == RuleComparison.ADDED
        || comparison == RuleComparison.REMOVED;
  }
}
