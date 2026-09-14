package com.barrier.riskengine.risk.rule.interfaces;

import com.barrier.riskengine.risk.rule.context.RiskContext;

/**
 * Fonte de regras escritas pelo parceiro.
 *
 * <p><b>Declarada aqui, e não no módulo que a implementa.</b> O módulo {@code riskpolicy}
 * precisa de {@code RiskRule}, {@code RiskResult} e {@code RiskContext}, todos deste módulo;
 * declarar a interface lá fecharia o ciclo {@code risk → riskpolicy → risk}, que o ArchUnit
 * ({@code sem_ciclos_entre_modulos}) rejeita. Mesma inversão de
 * {@code AssuranceRecordedListener} e {@code AssessmentCompletedListener}.
 *
 * <p>⚠️ Pensada como bean único: {@code RiskScoringService} injeta {@code
 * Optional<CustomRuleSource>} (zero beans resolve para {@link CustomRules#NONE}), mas uma segunda
 * implementação sem {@code @Primary}/{@code @Qualifier} quebra a subida do contexto com {@code
 * NoUniqueBeanDefinitionException}.
 */
public interface CustomRuleSource {

  /**
   * Regras da política ativa do tenant desta avaliação; {@link CustomRules#NONE} se não houver.
   */
  CustomRules forContext(RiskContext context);
}
