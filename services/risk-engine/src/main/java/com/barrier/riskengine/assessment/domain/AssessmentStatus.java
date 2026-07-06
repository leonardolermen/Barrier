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
  EM_REVISAO
}
