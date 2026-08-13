package com.barrier.riskengine.risk.rule.interfaces;

import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.RiskContext;

/**
 * Regra de risco (Strategy). Cada regra avalia o contexto e devolve um {@link RiskResult}
 * padronizado (score, severidade, motivo, evidências e recomendação). Regras que não se
 * aplicam devolvem {@link RiskResult#notApplicable(String)}.
 *
 * <p>O motor apenas executa todas as regras e agrega — adicionar uma nova fonte (novo bureau,
 * lista de sanções, sinal de fraude) é adicionar uma regra, sem reescrever o motor.
 */
public interface RiskRule {

  RiskResult evaluate(RiskContext context);

  /**
   * Código estável da família de regra (ex.: {@code NEW_COMPANY}), usado pelo registry de
   * regras ({@code RiskRuleRegistryService}) para habilitar/desabilitar e definir vigência sem
   * deploy — independente do {@code ruleCode} granular que a regra pode variar em
   * {@link RiskResult} (ex.: {@code IdentityRiskRule} devolve códigos diferentes por desfecho,
   * mas pertence à família {@code IDENTITY}).
   */
  String code();

  /**
   * Parâmetros que <b>esta avaliação</b> usou nesta regra, já resolvidos (override do tenant ou
   * default global).
   *
   * <p>Existe para tornar uma decisão antiga reproduzível. Antes, o parâmetro efetivo só aparecia
   * na evidência da regra que <i>disparou</i> ({@code config:months=6}) — e
   * {@code tenant_risk_config} é mutável. Uma regra que passou não deixava rastro nenhum do valor
   * usado, então "por que este cliente não foi pego pela regra de empresa nova em março?" não
   * tinha resposta: o parâmetro de março pode ter sido outro, e não há como saber.
   *
   * <p>Vazio por padrão: regra sem configuração — a maioria, incluindo todas as regulatórias — não
   * tem o que registrar, e forçá-la a implementar isso encheria a trilha de mapas vazios.
   */
  default java.util.Map<String, String> effectiveParameters(RiskContext context) {
    return java.util.Map.of();
  }
}
