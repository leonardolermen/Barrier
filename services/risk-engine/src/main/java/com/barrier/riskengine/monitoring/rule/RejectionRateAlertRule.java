package com.barrier.riskengine.monitoring.rule;

import com.barrier.riskengine.monitoring.domain.Alert;
import com.barrier.riskengine.monitoring.domain.Baseline;
import com.barrier.riskengine.monitoring.service.PipelineSnapshot;
import com.barrier.riskengine.monitoring.service.interfaces.AlertRule;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code recusa_alta} — a taxa de recusa saltou em relação ao histórico desta hora.
 *
 * <p>Recusa em massa quase nunca é o cliente piorando de repente: é lista importada com layout novo
 * casando com todo mundo, bureau devolvendo situação cadastral errada, ou regra recém-publicada com
 * peso trocado. O dano é assimétrico e silencioso — cada recusa indevida é um cliente legítimo
 * barrado, que não abre chamado, só desiste.
 *
 * <p>Só a ponta alta é vigiada aqui. Recusa em queda já é coberta pela ponta alta de
 * {@code aprov_auto} — o que sobra da recusa vira aprovação —, e duas regras alertando sobre o
 * mesmo fenômeno dobrariam o ruído sem dobrar a informação.
 */
@Component
public class RejectionRateAlertRule implements AlertRule {

  private final double highFactor;
  private final long minCompleted;

  public RejectionRateAlertRule(
      @Value("${barrier.monitoring.rejection.high-factor:1.5}") double highFactor,
      @Value("${barrier.monitoring.min-completed:20}") long minCompleted) {
    this.highFactor = highFactor;
    this.minCompleted = minCompleted;
  }

  @Override
  public String code() {
    return "recusa_alta";
  }

  @Override
  public Optional<Alert> evaluate(PipelineSnapshot snapshot) {
    Double observado = snapshot.current().rejectionRate();
    if (observado == null || snapshot.current().completed() < minCompleted) {
      return Optional.empty();
    }
    Optional<Baseline> baseline = snapshot.rejectionBaseline();
    if (baseline.isEmpty() || !baseline.get().acimaDe(observado, highFactor)) {
      return Optional.empty();
    }
    Baseline esperado = baseline.get();
    return Optional.of(
        Alert.critical(
            code(),
            "Recusa muito acima do normal — possível lista com layout novo, bureau devolvendo"
                + " situação cadastral errada ou regra mal calibrada",
            String.format(
                "observado=%.1f%%; esperado≈%.1f%% (média de %d dias na mesma hora); base=%d"
                    + " conclusões",
                observado * 100,
                esperado.expected() * 100,
                esperado.samples(),
                snapshot.current().completed())));
  }
}
