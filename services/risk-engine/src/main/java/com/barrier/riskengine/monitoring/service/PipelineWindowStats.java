package com.barrier.riskengine.monitoring.service;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import java.util.Map;

/**
 * Fotografia de uma janela de uma hora do pipeline: quantas entraram e como as concluídas
 * terminaram. É a unidade que tanto a hora corrente quanto o histórico do baseline usam — a mesma
 * medida dos dois lados, senão a comparação não significa nada.
 *
 * @param autoApproved aprovações <b>sem</b> passagem por decisão humana. Aprovado pela mesa não
 *     conta: o que esta série vigia é o motor, e somar as duas origens esconderia a deriva que se
 *     quer ver — mesa aprovando demais e motor aprovando demais são incidentes distintos, com
 *     causas distintas.
 */
public record PipelineWindowStats(
    long intake, Map<AssessmentStatus, Long> completedByStatus, long autoApproved) {

  public long completed() {
    return completedByStatus.values().stream().mapToLong(Long::longValue).sum();
  }

  public long count(AssessmentStatus status) {
    return completedByStatus.getOrDefault(status, 0L);
  }

  /**
   * Fração das conclusões que foi aprovação automática.
   *
   * @return {@code null} quando não houve conclusão na janela — não existe taxa, e devolver zero
   *     seria afirmar que o motor não aprovou nada, que é uma informação diferente (e é justamente
   *     a que dispara {@code aprov_auto_baixo})
   */
  public Double autoApprovalRate() {
    long total = completed();
    return total == 0 ? null : (double) autoApproved / total;
  }

  /** Fração das conclusões que foi recusa. {@code null} pelo mesmo motivo acima. */
  public Double rejectionRate() {
    long total = completed();
    return total == 0 ? null : (double) count(AssessmentStatus.REPROVADO) / total;
  }
}
