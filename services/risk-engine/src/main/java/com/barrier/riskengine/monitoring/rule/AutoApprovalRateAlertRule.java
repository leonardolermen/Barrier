package com.barrier.riskengine.monitoring.rule;

import com.barrier.riskengine.monitoring.domain.Alert;
import com.barrier.riskengine.monitoring.domain.Baseline;
import com.barrier.riskengine.monitoring.service.PipelineSnapshot;
import com.barrier.riskengine.monitoring.service.interfaces.AlertRule;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code aprov_auto_alto} / {@code aprov_auto_baixo} — a taxa de aprovação automática saiu da faixa
 * histórica para esta hora.
 *
 * <p><b>É o alerta mais valioso dos quatro</b>, porque pega três incidentes distintos com a mesma
 * série: regra de risco desligada por engano no registry, provider devolvendo resposta vazia
 * (bureau ou watchlist "limpos" aprovam tudo — o fail-open que a auditoria mandou eliminar e que
 * já reapareceu duas vezes neste código: {@code ScreeningCoverageRiskRule} e
 * {@code CorporateStructureCoverageRiskRule} existem por causa dele), e fraude em escala. Nenhum
 * dos três se anuncia de outro jeito, e nenhum cruza limiar absoluto.
 *
 * <p>A ponta <b>baixa</b> importa tanto quanto a alta, por um motivo operacional já vivido: 7501 de
 * 7529 avaliações caíram em EM_REVISAO por ruído de cadastro e cegaram a operação
 * ({@code plano-remediacao-auditoria.md}). Aprovação automática despencando é o primeiro sintoma
 * disso, e chega antes de a fila da mesa transbordar.
 */
@Component
public class AutoApprovalRateAlertRule implements AlertRule {

  private final double highFactor;
  private final double lowFraction;
  private final long minCompleted;

  public AutoApprovalRateAlertRule(
      @Value("${barrier.monitoring.auto-approval.high-factor:1.3}") double highFactor,
      @Value("${barrier.monitoring.auto-approval.low-fraction:0.7}") double lowFraction,
      @Value("${barrier.monitoring.min-completed:20}") long minCompleted) {
    this.highFactor = highFactor;
    this.lowFraction = lowFraction;
    this.minCompleted = minCompleted;
  }

  @Override
  public String code() {
    return "aprov_auto";
  }

  @Override
  public Optional<Alert> evaluate(PipelineSnapshot snapshot) {
    Double observado = snapshot.current().autoApprovalRate();
    // Taxa sobre amostra pequena oscila sozinha: 3 conclusões viram 0%, 33% ou 100% sem que nada
    // tenha mudado. Alertar sobre isso ensina o time a ignorar o canal.
    if (observado == null || snapshot.current().completed() < minCompleted) {
      return Optional.empty();
    }
    Optional<Baseline> baseline = snapshot.autoApprovalBaseline();
    if (baseline.isEmpty()) {
      return Optional.empty();
    }
    Baseline esperado = baseline.get();

    if (esperado.acimaDe(observado, highFactor)) {
      return Optional.of(
          Alert.critical(
              "aprov_auto_alto",
              "Aprovação automática muito acima do normal — possível regra desligada, provider"
                  + " devolvendo vazio ou fraude em escala",
              evidencia(observado, esperado, snapshot)));
    }
    if (esperado.abaixoDe(observado, lowFraction)) {
      return Optional.of(
          Alert.warning(
              "aprov_auto_baixo",
              "Aprovação automática muito abaixo do normal — a fila de revisão manual tende a"
                  + " transbordar",
              evidencia(observado, esperado, snapshot)));
    }
    return Optional.empty();
  }

  private String evidencia(double observado, Baseline esperado, PipelineSnapshot snapshot) {
    return String.format(
        "observado=%.1f%%; esperado≈%.1f%% (média de %d dias na mesma hora); base=%d conclusões",
        observado * 100, esperado.expected() * 100, esperado.samples(), snapshot.current().completed());
  }
}
