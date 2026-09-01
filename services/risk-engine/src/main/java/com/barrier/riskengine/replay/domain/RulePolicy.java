package com.barrier.riskengine.replay.domain;

import com.barrier.riskengine.policy.ParamAuthorship;
import com.barrier.riskengine.policy.RegistryPolicyState;
import java.util.List;

/**
 * A política que vigia sobre esta regra no instante da decisão — a metade que a trilha da avaliação
 * não conta.
 *
 * <p>{@code evaluated_json} responde <b>o quê</b>: a regra rodou, passou, ou ficou {@code
 * SUPPRESSED}, e com que parâmetro. Não responde <b>quem</b>: uma regra regulatória desligada por
 * uma semana e religada aparece na trilha como suprimida, sem nome nem data de quem a desligou. É a
 * operação mais sensível do sistema, e era a única sem autoria até a V033 — que passou a gravá-la e
 * que, até aqui, ninguém lia.
 *
 * @param registry estado do registry no instante; {@code null} quando a autoria não é apurável
 *     (histórico inteiramente posterior à decisão — ver {@code PolicyProvenance})
 * @param parameters autoria de cada parâmetro efetivo que a regra usou. Vazio para regra sem
 *     configuração, que é a maioria — inclusive todas as regulatórias
 */
public record RulePolicy(RegistryPolicyState registry, List<ParamAuthorship> parameters) {

  public RulePolicy {
    parameters = parameters == null ? List.of() : List.copyOf(parameters);
  }

  /** Sem nenhuma informação de política — nem registry, nem parâmetro. */
  public static RulePolicy desconhecida() {
    return new RulePolicy(null, List.of());
  }
}
