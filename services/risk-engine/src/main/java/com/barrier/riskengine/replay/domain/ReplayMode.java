package com.barrier.riskengine.replay.domain;

/** O que se pede ao replay. Os dois modos respondem perguntas diferentes. */
public enum ReplayMode {

  /**
   * <b>Reconstrução.</b> Não reexecuta regra nenhuma: monta o dossiê do que foi decidido a partir do
   * que está gravado — evidência exata, todas as regras com desfecho e parâmetro efetivo, versões
   * das listas consultadas — e <b>reconfere a aritmética</b>, recalculando soma, banda e recomendação
   * a partir dos resultados persistidos e comparando com o gravado em {@code risk_scores}.
   *
   * <p>É o modo que responde ao fiscal, e o único que é sempre exato.
   */
  AS_DECIDED,

  /**
   * <b>Reexecução.</b> Roda as regras <b>de hoje</b> sobre a <b>evidência gravada</b> e aponta a
   * diferença regra a regra. Nenhuma chamada de bureau, nenhum screening novo, nenhuma consulta
   * paga.
   *
   * <p>Não reproduz a lógica da época: regra é código, não dado, e o código que produziu uma decisão
   * de {@code barrier-risk-rules/1.4.0} não existe mais no binário. A pergunta que este modo
   * responde é a outra — <i>o motor de hoje decidiria o mesmo?</i>
   */
  CURRENT_ENGINE
}
