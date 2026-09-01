package com.barrier.riskengine.policy;

/**
 * De onde veio a afirmação sobre a política que vigia num instante — e, quando não veio de lugar
 * nenhum, isso é dito.
 *
 * <p>Mesma disciplina do replay de decisão: a lacuna é apurada e declarada, nunca presumida. Uma
 * resposta que dissesse "regra habilitada" sem saber se era esse o estado em março responderia a
 * pergunta errada com confiança.
 */
public enum PolicyProvenance {

  /** Há entrada de histórico em vigor no instante consultado: estado e autoria são conhecidos. */
  FROM_HISTORY,

  /**
   * Não há nenhuma alteração registrada para esta chave. O que se lê hoje é o que vigia desde a
   * semente da migration — logo, também vigia no instante consultado.
   */
  UNCHANGED_SINCE_SEED,

  /**
   * A regra <b>nunca teve linha no registry</b>, e nunca teve histórico. Não é lacuna: o registry é
   * um kill switch, não uma allowlist, e regra sem linha é ativa por decisão de desenho
   * (fail-open, ver {@code RiskRuleRegistryService.isActive}). Logo, estava ativa no instante
   * consultado — sem autor, porque ninguém precisou agir para que fosse assim.
   *
   * <p>Existe porque confundir isto com {@link #UNKNOWN_BEFORE_HISTORY} marcaria como "autoria não
   * apurável" a maioria das regras do motor: a V016 semeia seis, e o motor tem dezesseis.
   */
  NOT_REGISTERED,

  /**
   * Há histórico, mas <b>todo ele é posterior</b> ao instante consultado. O estado anterior à
   * primeira alteração registrada não foi gravado: {@code risk_rule_registry_history} guarda o
   * estado <i>novo</i> de cada mudança, e a versão anterior à primeira não existe em lugar nenhum.
   *
   * <p>Limitação estrutural do desenho da V033, não defeito de leitura. O que se perde é a
   * <b>autoria</b>; o desfecho da regra naquela avaliação continua gravado em {@code
   * evaluated_json}, que registra inclusive as regras suprimidas.
   */
  UNKNOWN_BEFORE_HISTORY
}
