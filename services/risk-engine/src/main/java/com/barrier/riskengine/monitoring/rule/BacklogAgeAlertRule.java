package com.barrier.riskengine.monitoring.rule;

import com.barrier.riskengine.monitoring.domain.Alert;
import com.barrier.riskengine.monitoring.service.PipelineSnapshot;
import com.barrier.riskengine.monitoring.service.interfaces.AlertRule;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code backlog_analise} — a avaliação mais antiga em EM_ANALISE passou do tempo aceitável.
 *
 * <p><b>É o alerta que o Barrier já precisou e não tinha.</b> No teste de carga registrado no
 * ADR-0015, 70.558 avaliações entraram, ~800 concluíram e 69.809 ficaram presas em EM_ANALISE — sem
 * erro, sem latência ruim, sem alerta. {@code PipelineHealthMetrics} já media a idade da mais
 * antiga; ninguém comparava essa medida com nada, e "pico absorvido" seguia indistinguível de
 * "afogando".
 *
 * <p>Este é o único dos quatro que usa <b>limiar fixo</b>, e de propósito: existe um tempo máximo
 * aceitável para uma decisão de KYC ficar pendente que independe do que costuma acontecer. Se a
 * fila normalmente demora seis horas, o baseline aprenderia que seis horas é o normal e pararia de
 * avisar — que é exatamente o erro que o alerta existe para não cometer.
 */
@Component
public class BacklogAgeAlertRule implements AlertRule {

  private final Duration warning;
  private final Duration critical;

  public BacklogAgeAlertRule(
      @Value("${barrier.monitoring.backlog.warning:PT15M}") Duration warning,
      @Value("${barrier.monitoring.backlog.critical:PT1H}") Duration critical) {
    this.warning = warning;
    this.critical = critical;
  }

  @Override
  public String code() {
    return "backlog_analise";
  }

  @Override
  public Optional<Alert> evaluate(PipelineSnapshot snapshot) {
    Duration idade = snapshot.oldestPending();
    if (idade.compareTo(critical) >= 0) {
      return Optional.of(
          Alert.critical(
              code(),
              "Fila de análise represada: a avaliação mais antiga está pendente há "
                  + humano(idade),
              "mais antiga=" + humano(idade) + "; limite crítico=" + humano(critical)));
    }
    if (idade.compareTo(warning) >= 0) {
      return Optional.of(
          Alert.warning(
              code(),
              "Fila de análise acumulando: mais antiga pendente há " + humano(idade),
              "mais antiga=" + humano(idade) + "; limite=" + humano(warning)));
    }
    return Optional.empty();
  }

  private static String humano(Duration d) {
    long minutos = d.toMinutes();
    return minutos < 60 ? minutos + "min" : (minutos / 60) + "h" + String.format("%02d", minutos % 60);
  }
}
