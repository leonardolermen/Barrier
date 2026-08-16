package com.barrier.riskengine.monitoring.client;

import com.barrier.riskengine.monitoring.domain.Alert;
import com.barrier.riskengine.monitoring.service.interfaces.AlertNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Notificador padrão: escreve o alerta no log, em nível proporcional à severidade.
 *
 * <p>Existe para que ligar o monitoramento não dependa de contratar canal nenhum — em qualquer
 * ambiente com coleta de log já dá para construir o disparo. Slack/PagerDuty entram como outra
 * implementação de {@link AlertNotifier}, sem tocar em regra nem no avaliador.
 *
 * <p>Alerta nunca carrega documento nem nome: a mensagem descreve agregados do pipeline, e é isso
 * que permite mandá-la para um canal com controle de acesso mais fraco que o do banco.
 */
@Component
public class LoggingAlertNotifier implements AlertNotifier {

  private static final Logger log = LoggerFactory.getLogger(LoggingAlertNotifier.class);

  @Override
  public void notify(Alert alert) {
    switch (alert.severity()) {
      case CRITICAL -> log.error("[ALERTA {}] {} — {}", alert.code(), alert.message(), alert.evidence());
      case WARNING -> log.warn("[ALERTA {}] {} — {}", alert.code(), alert.message(), alert.evidence());
    }
  }
}
