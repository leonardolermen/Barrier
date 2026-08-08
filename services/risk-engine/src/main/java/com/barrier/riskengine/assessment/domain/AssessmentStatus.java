package com.barrier.riskengine.assessment.domain;

/** Estado do ciclo de vida de uma avaliação. */
public enum AssessmentStatus {
  /** Recebida, aguardando processamento. */
  EM_ANALISE,
  /** Concluída com aprovação automática. */
  APROVADO,
  /** Concluída com reprovação. */
  REPROVADO,
  /** Encaminhada para análise manual (EDD) — decisão em Case Management (fase 2). */
  EM_REVISAO,
  /**
   * Não foi possível concluir o processamento após as tentativas previstas.
   *
   * <p>Existe para tirar a avaliação do limbo: antes, uma falha repetida a deixava EM_ANALISE
   * para sempre, sendo reprocessada a cada 2 segundos, e o cliente não tinha como distinguir
   * "ainda processando" de "nunca vai concluir". É estado terminal automático — a retomada é
   * operacional (corrigir a causa e reenfileirar), não silenciosa.
   */
  FALHA_PROCESSAMENTO
}
