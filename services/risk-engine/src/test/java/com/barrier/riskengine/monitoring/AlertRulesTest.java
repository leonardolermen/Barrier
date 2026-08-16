package com.barrier.riskengine.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.monitoring.domain.Alert;
import com.barrier.riskengine.monitoring.rule.AutoApprovalRateAlertRule;
import com.barrier.riskengine.monitoring.rule.BacklogAgeAlertRule;
import com.barrier.riskengine.monitoring.rule.IntakeVolumeAlertRule;
import com.barrier.riskengine.monitoring.rule.RejectionRateAlertRule;
import com.barrier.riskengine.monitoring.service.PipelineSnapshot;
import com.barrier.riskengine.monitoring.service.PipelineWindowStats;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * As regras contra série sintética. O que estes testes protegem, mais que o disparo, é o
 * <b>não</b>-disparo: alerta que grita sem motivo é treinado a ser ignorado, e aí ele não serve
 * quando o incidente real chegar.
 */
class AlertRulesTest {

  private final BacklogAgeAlertRule backlog =
      new BacklogAgeAlertRule(Duration.ofMinutes(15), Duration.ofHours(1));
  private final IntakeVolumeAlertRule intake = new IntakeVolumeAlertRule(0.3);
  private final AutoApprovalRateAlertRule autoApproval =
      new AutoApprovalRateAlertRule(1.3, 0.7, 20);
  private final RejectionRateAlertRule rejection = new RejectionRateAlertRule(1.5, 20);

  // --- backlog_analise -------------------------------------------------------

  /** O cenário do teste de carga do ADR-0015: a fila para de vazar e nada mais acusa. */
  @Test
  void fila_represada_dispara_critico() {
    PipelineSnapshot snapshot = snapshot(vazia(), historico(vazia(), 7), Duration.ofHours(3));

    Optional<Alert> alerta = backlog.evaluate(snapshot);

    assertThat(alerta).isPresent();
    assertThat(alerta.get().severity()).isEqualTo(Alert.Severity.CRITICAL);
    assertThat(alerta.get().evidence()).contains("3h00");
  }

  @Test
  void fila_saudavel_nao_dispara() {
    assertThat(backlog.evaluate(snapshot(vazia(), historico(vazia(), 7), Duration.ofMinutes(2))))
        .isEmpty();
  }

  // --- vol_hora_baixo --------------------------------------------------------

  @Test
  void queda_de_entrada_contra_o_historico_da_mesma_hora_dispara() {
    PipelineWindowStats agora = new PipelineWindowStats(3, Map.of(), 0);

    Optional<Alert> alerta =
        intake.evaluate(
            snapshot(agora, historico(new PipelineWindowStats(100, Map.of(), 0), 7), Duration.ZERO));

    assertThat(alerta).isPresent();
    assertThat(alerta.get().code()).isEqualTo("vol_hora_baixo");
    assertThat(alerta.get().evidence()).contains("observado=3");
  }

  /** Instalação nova não tem expectativa; alertar no primeiro dia é o alerta mentindo. */
  @Test
  void historico_insuficiente_nao_dispara() {
    PipelineWindowStats agora = new PipelineWindowStats(0, Map.of(), 0);

    assertThat(
            intake.evaluate(
                snapshot(agora, historico(new PipelineWindowStats(100, Map.of(), 0), 2), Duration.ZERO)))
        .isEmpty();
  }

  /** Madrugada historicamente vazia não tem queda a detectar. */
  @Test
  void hora_historicamente_vazia_nao_dispara() {
    PipelineWindowStats agora = new PipelineWindowStats(0, Map.of(), 0);

    assertThat(
            intake.evaluate(
                snapshot(agora, historico(new PipelineWindowStats(0, Map.of(), 0), 7), Duration.ZERO)))
        .isEmpty();
  }

  // --- aprov_auto_alto / aprov_auto_baixo ------------------------------------

  /** Regra desligada por engano, provider devolvendo vazio ou fraude em escala: mesma assinatura. */
  @Test
  void salto_de_aprovacao_automatica_dispara_critico() {
    PipelineWindowStats agora = conclusoes(98, 2, 0, 98);
    PipelineWindowStats normal = conclusoes(60, 10, 30, 60);

    Optional<Alert> alerta = autoApproval.evaluate(snapshot(agora, historico(normal, 7), Duration.ZERO));

    assertThat(alerta).isPresent();
    assertThat(alerta.get().code()).isEqualTo("aprov_auto_alto");
    assertThat(alerta.get().severity()).isEqualTo(Alert.Severity.CRITICAL);
  }

  @Test
  void queda_de_aprovacao_automatica_dispara_aviso() {
    PipelineWindowStats agora = conclusoes(10, 5, 85, 10);
    PipelineWindowStats normal = conclusoes(60, 10, 30, 60);

    Optional<Alert> alerta = autoApproval.evaluate(snapshot(agora, historico(normal, 7), Duration.ZERO));

    assertThat(alerta).isPresent();
    assertThat(alerta.get().code()).isEqualTo("aprov_auto_baixo");
  }

  /** Três conclusões viram 0%, 33% ou 100% sem nada ter mudado. */
  @Test
  void amostra_pequena_na_hora_corrente_nao_dispara() {
    PipelineWindowStats agora = conclusoes(3, 0, 0, 3);
    PipelineWindowStats normal = conclusoes(60, 10, 30, 60);

    assertThat(autoApproval.evaluate(snapshot(agora, historico(normal, 7), Duration.ZERO))).isEmpty();
  }

  @Test
  void taxa_dentro_da_faixa_nao_dispara() {
    PipelineWindowStats agora = conclusoes(62, 10, 28, 62);
    PipelineWindowStats normal = conclusoes(60, 10, 30, 60);

    assertThat(autoApproval.evaluate(snapshot(agora, historico(normal, 7), Duration.ZERO))).isEmpty();
  }

  /**
   * Janela histórica sem conclusão (madrugada parada) não pode entrar como 0% — rebaixaria a
   * expectativa até o alerta nunca disparar.
   */
  @Test
  void janela_historica_sem_conclusao_e_descartada_do_baseline() {
    PipelineWindowStats agora = conclusoes(98, 2, 0, 98);
    List<PipelineWindowStats> historico = new ArrayList<>(historico(conclusoes(60, 10, 30, 60), 4));
    historico.add(vazia());
    historico.add(vazia());

    Optional<Alert> alerta = autoApproval.evaluate(snapshot(agora, historico, Duration.ZERO));

    assertThat(alerta).isPresent();
    assertThat(alerta.get().code()).isEqualTo("aprov_auto_alto");
  }

  // --- recusa_alta -----------------------------------------------------------

  @Test
  void salto_de_recusa_dispara_critico() {
    PipelineWindowStats agora = conclusoes(20, 70, 10, 20);
    PipelineWindowStats normal = conclusoes(60, 10, 30, 60);

    Optional<Alert> alerta = rejection.evaluate(snapshot(agora, historico(normal, 7), Duration.ZERO));

    assertThat(alerta).isPresent();
    assertThat(alerta.get().code()).isEqualTo("recusa_alta");
  }

  @Test
  void recusa_estavel_nao_dispara() {
    PipelineWindowStats normal = conclusoes(60, 10, 30, 60);

    assertThat(rejection.evaluate(snapshot(normal, historico(normal, 7), Duration.ZERO))).isEmpty();
  }

  // --- fixtures --------------------------------------------------------------

  private static PipelineWindowStats vazia() {
    return new PipelineWindowStats(0, Map.of(), 0);
  }

  private static PipelineWindowStats conclusoes(
      long aprovado, long reprovado, long emRevisao, long automaticas) {
    return new PipelineWindowStats(
        aprovado + reprovado + emRevisao,
        Map.of(
            AssessmentStatus.APROVADO, aprovado,
            AssessmentStatus.REPROVADO, reprovado,
            AssessmentStatus.EM_REVISAO, emRevisao),
        automaticas);
  }

  private static List<PipelineWindowStats> historico(PipelineWindowStats janela, int dias) {
    return IntStream.range(0, dias).mapToObj(i -> janela).toList();
  }

  private static PipelineSnapshot snapshot(
      PipelineWindowStats agora, List<PipelineWindowStats> historico, Duration maisAntiga) {
    return new PipelineSnapshot(agora, historico, maisAntiga);
  }
}
