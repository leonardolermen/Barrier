package com.barrier.riskengine.mesa;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.mesa.domain.CaseAction;
import com.barrier.riskengine.mesa.domain.CaseActionType;
import com.barrier.riskengine.mesa.domain.SlaClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * O SLA pausável. O que estes testes guardam é a diferença entre "a mesa está devagar" e "estamos
 * esperando o cliente" — sem ela, o indicador mede a lentidão do parceiro e culpa o analista.
 */
class SlaClockTest {

  private static final Instant T0 = Instant.parse("2026-08-16T09:00:00Z");
  private static final UUID CASE = UUID.randomUUID();

  @Test
  void caso_sem_espera_consome_o_tempo_inteiro() {
    Duration elapsed = SlaClock.elapsed(T0, T0.plus(Duration.ofHours(3)), List.of(), agora());

    assertThat(elapsed).isEqualTo(Duration.ofHours(3));
  }

  @Test
  void espera_com_pedido_e_recebimento_e_descontada() {
    var actions =
        List.of(
            action(CaseActionType.DOCUMENT_REQUESTED, T0.plus(Duration.ofHours(1))),
            action(CaseActionType.DOCUMENT_RECEIVED, T0.plus(Duration.ofHours(5))));

    Duration elapsed = SlaClock.elapsed(T0, T0.plus(Duration.ofHours(6)), actions, agora());

    // 6h de vida, 4h esperando o parceiro → 2h de mesa
    assertThat(elapsed).isEqualTo(Duration.ofHours(2));
  }

  /**
   * A regra que impede a mesa de zerar o próprio SLA: bastaria pedir um documento e nunca registrar
   * o recebimento. Sem registro de saída, o intervalo é descartado — conta contra a mesa.
   */
  @Test
  void pedido_sem_recebimento_nao_vira_pausa() {
    var actions = List.of(action(CaseActionType.DOCUMENT_REQUESTED, T0.plus(Duration.ofHours(1))));

    Duration elapsed = SlaClock.elapsed(T0, T0.plus(Duration.ofHours(10)), actions, agora());

    assertThat(elapsed).isEqualTo(Duration.ofHours(10));
  }

  @Test
  void recebimento_sem_pedido_e_ignorado() {
    var actions = List.of(action(CaseActionType.DOCUMENT_RECEIVED, T0.plus(Duration.ofHours(2))));

    Duration elapsed = SlaClock.elapsed(T0, T0.plus(Duration.ofHours(4)), actions, agora());

    assertThat(elapsed).isEqualTo(Duration.ofHours(4));
  }

  /** Cobrança repetida do mesmo documento é a mesma espera, não duas. */
  @Test
  void pedido_repetido_antes_do_recebimento_nao_abre_segunda_janela() {
    var actions =
        List.of(
            action(CaseActionType.DOCUMENT_REQUESTED, T0.plus(Duration.ofHours(1))),
            action(CaseActionType.DOCUMENT_REQUESTED, T0.plus(Duration.ofHours(2))),
            action(CaseActionType.DOCUMENT_RECEIVED, T0.plus(Duration.ofHours(4))));

    Duration elapsed = SlaClock.elapsed(T0, T0.plus(Duration.ofHours(5)), actions, agora());

    // Espera única de 1h→4h = 3h; sobra 2h de mesa.
    assertThat(elapsed).isEqualTo(Duration.ofHours(2));
  }

  @Test
  void duas_esperas_distintas_somam() {
    var actions =
        List.of(
            action(CaseActionType.DOCUMENT_REQUESTED, T0.plus(Duration.ofHours(1))),
            action(CaseActionType.DOCUMENT_RECEIVED, T0.plus(Duration.ofHours(2))),
            action(CaseActionType.DOCUMENT_REQUESTED, T0.plus(Duration.ofHours(4))),
            action(CaseActionType.DOCUMENT_RECEIVED, T0.plus(Duration.ofHours(6))));

    Duration elapsed = SlaClock.elapsed(T0, T0.plus(Duration.ofHours(8)), actions, agora());

    // 8h de vida − (1h + 2h) = 5h
    assertThat(elapsed).isEqualTo(Duration.ofHours(5));
  }

  @Test
  void caso_aberto_conta_ate_agora() {
    Instant agora = T0.plus(Duration.ofHours(2));

    Duration elapsed = SlaClock.elapsed(T0, null, List.of(), agora);

    assertThat(elapsed).isEqualTo(Duration.ofHours(2));
  }

  /** Espera que começou antes da abertura do caso não pode descontar mais do que o caso viveu. */
  @Test
  void espera_fora_da_vida_do_caso_e_recortada() {
    var actions =
        List.of(
            action(CaseActionType.DOCUMENT_REQUESTED, T0.minus(Duration.ofHours(5))),
            action(CaseActionType.DOCUMENT_RECEIVED, T0.plus(Duration.ofHours(1))));

    Duration elapsed = SlaClock.elapsed(T0, T0.plus(Duration.ofHours(3)), actions, agora());

    // Desconta só a 1h que caiu dentro da vida do caso.
    assertThat(elapsed).isEqualTo(Duration.ofHours(2));
  }

  @Test
  void nunca_devolve_negativo() {
    var actions =
        List.of(
            action(CaseActionType.DOCUMENT_REQUESTED, T0),
            action(CaseActionType.DOCUMENT_RECEIVED, T0.plus(Duration.ofHours(3))));

    Duration elapsed = SlaClock.elapsed(T0, T0.plus(Duration.ofHours(3)), actions, agora());

    assertThat(elapsed).isEqualTo(Duration.ZERO);
  }

  /** Ações fora de ordem cronológica não podem mudar o resultado. */
  @Test
  void ordem_de_chegada_das_acoes_nao_importa() {
    var forade =
        List.of(
            action(CaseActionType.DOCUMENT_RECEIVED, T0.plus(Duration.ofHours(5))),
            action(CaseActionType.NOTE, T0.plus(Duration.ofHours(3))),
            action(CaseActionType.DOCUMENT_REQUESTED, T0.plus(Duration.ofHours(1))));

    Duration elapsed = SlaClock.elapsed(T0, T0.plus(Duration.ofHours(6)), forade, agora());

    assertThat(elapsed).isEqualTo(Duration.ofHours(2));
  }

  private static Instant agora() {
    return T0.plus(Duration.ofDays(1));
  }

  private static CaseAction action(CaseActionType type, Instant when) {
    return new CaseAction(UUID.randomUUID(), CASE, "acme", type, "analista@empresa", null, when);
  }
}
