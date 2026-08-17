package com.barrier.riskengine.monitoring.service;

import com.barrier.riskengine.monitoring.domain.Baseline;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Tudo que as regras de alerta precisam, medido uma vez por ciclo: a hora corrente, as mesmas horas
 * de dias anteriores (o histórico do baseline) e a idade da avaliação pendente mais antiga.
 *
 * @param oldestPending idade do item mais antigo em EM_ANALISE; {@link Duration#ZERO} se a fila
 *     está vazia
 * @param history janelas de <b>mesma hora do dia</b> em dias anteriores, da mais recente para a
 *     mais antiga
 */
public record PipelineSnapshot(
    PipelineWindowStats current, List<PipelineWindowStats> history, Duration oldestPending) {

  /** Baseline do volume de intake para esta hora do dia. */
  public Optional<Baseline> intakeBaseline() {
    return Baseline.of(history.stream().map(PipelineWindowStats::intake).toList());
  }

  /**
   * Baseline da taxa de aprovação automática. Janelas históricas <b>sem conclusão</b> são
   * descartadas em vez de contarem como zero: madrugada parada não é "o motor aprovava 0%", e
   * tratá-la assim rebaixaria a expectativa até o alerta de deriva nunca disparar.
   */
  public Optional<Baseline> autoApprovalBaseline() {
    return Baseline.of(
        history.stream().map(PipelineWindowStats::autoApprovalRate).filter(java.util.Objects::nonNull).toList());
  }

  /** Baseline da taxa de recusa; mesma regra de descarte. */
  public Optional<Baseline> rejectionBaseline() {
    return Baseline.of(
        history.stream().map(PipelineWindowStats::rejectionRate).filter(java.util.Objects::nonNull).toList());
  }
}
