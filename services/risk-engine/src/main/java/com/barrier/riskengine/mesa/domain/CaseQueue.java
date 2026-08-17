package com.barrier.riskengine.mesa.domain;

/**
 * Filas nomeadas da mesa. Fila é propriedade do caso, não do analista: é o que permite dizer
 * "quantos casos estão em alçada de risco" sem depender de quem está de plantão.
 */
public enum CaseQueue {

  /** Revisão comum (EDD). É onde nasce todo caso vindo de `EM_REVISAO`. */
  ANALISE_PADRAO,

  /** Casos que exigem alçada superior. Movimentação manual. */
  ALCADA_RISCO,

  /**
   * Esperando documento ou resposta do parceiro.
   *
   * <p>É a única fila cujo tempo <b>pode</b> não consumir SLA — e só quando a espera é provável.
   * Ver {@link SlaClock}.
   */
  AGUARDANDO_PARCEIRO
}
