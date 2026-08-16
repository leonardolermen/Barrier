package com.barrier.riskengine.monitoring.service.interfaces;

import com.barrier.riskengine.monitoring.domain.Alert;

/**
 * Destino de um alerta. Interface porque Slack/PagerDuty é integração externa, e integração externa
 * no Barrier entra por interface do pacote {@code client} — a implementação padrão só loga, para
 * que ligar o monitoramento não dependa de contratar canal nenhum.
 */
public interface AlertNotifier {

  void notify(Alert alert);
}
