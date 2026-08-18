package com.barrier.riskengine.monitoring.client;

import com.barrier.riskengine.monitoring.domain.Alert;
import com.barrier.riskengine.monitoring.service.interfaces.AlertNotifier;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Envia alertas para o PagerDuty (Events API v2).
 *
 * <p>É o que faz o F5 sair do papel: até aqui a única implementação de {@link AlertNotifier}
 * escrevia em log, então uma fila represada às 3h da manhã disparava alerta para um arquivo. Detectar
 * sem avisar é quase o mesmo que não detectar.
 *
 * <p><b>{@code dedup_key} é o código do alerta.</b> O PagerDuty agrupa eventos com a mesma chave num
 * único incidente: {@code backlog_analise} disparando a cada ciclo enquanto a fila está represada
 * atualiza o incidente aberto em vez de criar dezenas. O dedup por tempo do {@code AlertEvaluator}
 * continua valendo — ele evita o tráfego; este evita o ruído do lado de lá.
 *
 * <p><b>Sem PII, por construção.</b> Alerta descreve agregados do pipeline (contagens, taxas,
 * idade da fila) e nunca documento ou nome — é o que permite mandá-lo para um serviço externo sem
 * base legal específica. Ver {@code Alert}.
 *
 * <p>A chave de integração vem de env ({@code PAGERDUTY_ROUTING_KEY}), nunca do código. Desligado
 * por padrão: sem chave configurada o bean não sobe, e o {@code LoggingAlertNotifier} continua
 * sendo o destino.
 */
@Component
@ConditionalOnProperty(value = "barrier.monitoring.pagerduty.enabled", havingValue = "true")
public class PagerDutyAlertNotifier implements AlertNotifier {

  private static final Logger log = LoggerFactory.getLogger(PagerDutyAlertNotifier.class);

  private final RestClient client;
  private final String routingKey;
  private final String source;

  public PagerDutyAlertNotifier(
      @Qualifier("pagerDutyRestClient") RestClient client,
      @Value("${barrier.monitoring.pagerduty.routing-key:}") String routingKey,
      @Value("${barrier.monitoring.pagerduty.source:barrier-risk-engine}") String source) {
    this.client = client;
    this.routingKey = routingKey;
    this.source = source;
  }

  @Override
  public void notify(Alert alert) {
    if (routingKey.isBlank()) {
      // Ligado sem chave é erro de configuração, não motivo para derrubar o ciclo de alertas: o
      // LoggingAlertNotifier já registrou o mesmo alerta, então nada se perde silenciosamente.
      log.error(
          "PagerDuty habilitado sem PAGERDUTY_ROUTING_KEY; alerta {} não foi enviado", alert.code());
      return;
    }
    client
        .post()
        .uri("/v2/enqueue")
        .contentType(MediaType.APPLICATION_JSON)
        .body(payload(alert))
        .retrieve()
        .toBodilessEntity();
  }

  /**
   * Payload da Events API v2. Visível para teste — é o contrato que quebra em silêncio se mudar.
   *
   * <p>{@code severity} do PagerDuty aceita critical/error/warning/info; o mapeamento é direto, mas
   * {@code WARNING} vai como {@code warning} e não {@code error} de propósito: aviso que acorda
   * alguém deixa de ser aviso.
   */
  Map<String, Object> payload(Alert alert) {
    return Map.of(
        "routing_key", routingKey,
        "event_action", "trigger",
        "dedup_key", alert.code(),
        "payload",
            Map.of(
                "summary", alert.message(),
                "severity", alert.severity() == Alert.Severity.CRITICAL ? "critical" : "warning",
                "source", source,
                "component", "risk-engine",
                "custom_details", Map.of("code", alert.code(), "evidence", alert.evidence())));
  }
}
