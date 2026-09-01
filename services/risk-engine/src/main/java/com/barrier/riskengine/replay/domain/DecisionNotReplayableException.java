package com.barrier.riskengine.replay.domain;

/**
 * A avaliação existe, mas não há decisão para replayar — nenhuma linha em {@code risk_scores}.
 *
 * <p>Acontece com avaliação ainda em {@code EM_ANALISE} e com avaliação que terminou em
 * {@code FALHA_PROCESSAMENTO} sem chegar ao motor. Exceção própria, e não
 * {@code IllegalStateException}, pelo mesmo motivo de {@code DocumentGateNotSatisfiedException}: o
 * tipo da exceção é o que decide o que vira mensagem pública, e erro de programação tem de continuar
 * caindo em 500 sem detalhe.
 */
public class DecisionNotReplayableException extends RuntimeException {

  public DecisionNotReplayableException(String assessmentId) {
    super("Avaliação " + assessmentId + " ainda não tem decisão do motor de risco para replayar");
  }
}
