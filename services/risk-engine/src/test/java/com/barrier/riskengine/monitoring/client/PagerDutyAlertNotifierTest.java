package com.barrier.riskengine.monitoring.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.monitoring.domain.Alert;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * O payload da Events API v2 — contrato que quebra em silêncio se mudar (o PagerDuty responde 202
 * para evento aceito e 400 para malformado, e ninguém está olhando o retorno de um alerta).
 */
class PagerDutyAlertNotifierTest {

  private final PagerDutyAlertNotifier notifier =
      new PagerDutyAlertNotifier(
          RestClient.builder().baseUrl("http://localhost").build(), "chave-de-rotas", "barrier-test");

  @Test
  void critico_vai_como_critical_com_dedup_pelo_codigo() {
    Alert alerta =
        Alert.critical("backlog_analise", "Fila represada", "mais antiga=3h00; limite=1h00");

    Map<String, Object> payload = notifier.payload(alerta);

    assertThat(payload.get("event_action")).isEqualTo("trigger");
    // dedup pelo código: o mesmo alerta repetido atualiza o incidente aberto em vez de criar outro
    assertThat(payload.get("dedup_key")).isEqualTo("backlog_analise");
    @SuppressWarnings("unchecked")
    Map<String, Object> corpo = (Map<String, Object>) payload.get("payload");
    assertThat(corpo.get("severity")).isEqualTo("critical");
    assertThat(corpo.get("summary")).isEqualTo("Fila represada");
    assertThat(corpo.get("source")).isEqualTo("barrier-test");
  }

  /** Aviso que acorda alguém deixa de ser aviso. */
  @Test
  void aviso_vai_como_warning_nao_como_error() {
    Map<String, Object> payload =
        notifier.payload(Alert.warning("vol_hora_baixo", "Entrada baixa", "observado=3"));

    @SuppressWarnings("unchecked")
    Map<String, Object> corpo = (Map<String, Object>) payload.get("payload");
    assertThat(corpo.get("severity")).isEqualTo("warning");
  }

  /** A evidência viaja: alerta sem número obriga quem está de plantão a ir ao banco. */
  @Test
  void evidencia_vai_nos_detalhes() {
    Map<String, Object> payload =
        notifier.payload(Alert.critical("aprov_auto_alto", "Aprovação alta", "observado=98,0%"));

    @SuppressWarnings("unchecked")
    Map<String, Object> corpo = (Map<String, Object>) payload.get("payload");
    @SuppressWarnings("unchecked")
    Map<String, Object> detalhes = (Map<String, Object>) corpo.get("custom_details");
    assertThat(detalhes).containsEntry("code", "aprov_auto_alto");
    assertThat(detalhes.get("evidence").toString()).contains("98,0%");
  }

  /** Ligado sem chave é erro de configuração; não pode derrubar o ciclo de alertas. */
  @Test
  void sem_chave_configurada_nao_estoura() {
    var semChave =
        new PagerDutyAlertNotifier(
            RestClient.builder().baseUrl("http://localhost:1").build(), "", "barrier-test");

    semChave.notify(Alert.warning("vol_hora_baixo", "Entrada baixa", "observado=0"));
  }
}
