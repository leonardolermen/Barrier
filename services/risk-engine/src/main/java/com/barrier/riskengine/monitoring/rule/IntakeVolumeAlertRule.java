package com.barrier.riskengine.monitoring.rule;

import com.barrier.riskengine.monitoring.domain.Alert;
import com.barrier.riskengine.monitoring.domain.Baseline;
import com.barrier.riskengine.monitoring.service.PipelineSnapshot;
import com.barrier.riskengine.monitoring.service.interfaces.AlertRule;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code vol_hora_baixo} — entrou muito menos avaliação nesta hora do que o normal para esta hora.
 *
 * <p><b>A falha que só o baseline pega.</b> Um parceiro que para de mandar não gera erro nenhum: o
 * endpoint responde 200 para quem não chama, o health fica verde, as métricas técnicas ficam
 * ótimas — o sistema fica <i>melhor</i> por todos os indicadores enquanto o produto está parado.
 * Integração quebrada do lado do cliente, credencial expirada e fila do parceiro travada aparecem
 * todas assim, e só assim.
 *
 * <p>Volume <b>alto</b> não tem regra aqui de propósito: pico de entrada é evento comercial normal,
 * e o que ele ameaça (a fila não vazar) já é coberto por {@code backlog_analise}, que mede a
 * consequência em vez do sintoma.
 */
@Component
public class IntakeVolumeAlertRule implements AlertRule {

  private final double fraction;

  public IntakeVolumeAlertRule(
      @Value("${barrier.monitoring.intake.low-fraction:0.3}") double fraction) {
    this.fraction = fraction;
  }

  @Override
  public String code() {
    return "vol_hora_baixo";
  }

  @Override
  public Optional<Alert> evaluate(PipelineSnapshot snapshot) {
    Optional<Baseline> baseline = snapshot.intakeBaseline();
    if (baseline.isEmpty()) {
      return Optional.empty();
    }
    Baseline esperado = baseline.get();
    // Hora historicamente vazia (madrugada, fim de semana) não tem queda a detectar: exigir mais de
    // uma avaliação esperada evita alertar que "caiu de 0,3 para 0".
    if (esperado.expected() < 1) {
      return Optional.empty();
    }
    long observado = snapshot.current().intake();
    if (!esperado.abaixoDe(observado, fraction)) {
      return Optional.empty();
    }
    return Optional.of(
        Alert.warning(
            code(),
            "Entrada de avaliações muito abaixo do normal para este horário — possível parceiro"
                + " sem enviar",
            String.format(
                "observado=%d; esperado≈%.1f (média de %d dias na mesma hora); piso=%.0f%%",
                observado, esperado.expected(), esperado.samples(), fraction * 100)));
  }
}
