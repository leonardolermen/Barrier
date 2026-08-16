package com.barrier.riskengine.mesa.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Tempo que o caso consumiu <b>da mesa</b> — descontando a espera que é comprovadamente do parceiro.
 *
 * <p><b>Por que descontar.</b> Um caso em {@code AGUARDANDO_PARCEIRO} está parado esperando
 * documento de fora; contar esse tempo faz o SLA medir a lentidão do parceiro e culpar a mesa. Foi
 * o {@code sla_pausa_parceiro.py} do ecossistema Origem que ensinou isto, e é o tipo de detalhe que
 * só a operação real produz: no papel, "tempo até decidir" parece uma subtração.
 *
 * <p><b>A regra que torna o desconto honesto</b>, transcrita da postura do Origem: <i>só contamos
 * espera que dá para provar — sem registro de saída e fora da fila, o intervalo é descartado.</i>
 * Concretamente:
 *
 * <ul>
 *   <li>uma pausa exige o <b>par</b> {@code DOCUMENT_REQUESTED} → {@code DOCUMENT_RECEIVED};
 *   <li>pedido sem recebimento correspondente <b>não</b> vira pausa — o tempo conta contra a mesa.
 *       É conservador de propósito: a alternativa (descontar tudo depois do último pedido) daria à
 *       mesa um jeito trivial de zerar o próprio SLA, bastando pedir um documento e nunca fechar;
 *   <li>recebimento sem pedido anterior é ignorado, não estoura;
 *   <li>pausas sobrepostas não são somadas duas vezes.
 * </ul>
 *
 * <p>Função pura sobre a linha do tempo de ações: o SLA é <b>reconstruído</b>, nunca acumulado numa
 * coluna. Contador incremental se perde no primeiro reprocessamento e não tem como ser auditado
 * depois; a lista de ações é a evidência, e o número é derivado dela.
 */
public final class SlaClock {

  private SlaClock() {}

  /**
   * @param openedAt abertura do caso
   * @param closedAt encerramento; {@code null} enquanto o caso está aberto
   * @param actions ações do caso, em qualquer ordem
   * @param now instante de referência para caso ainda aberto
   * @return tempo decorrido menos as pausas comprováveis; nunca negativo
   */
  public static Duration elapsed(
      Instant openedAt, Instant closedAt, List<CaseAction> actions, Instant now) {
    Instant fim = closedAt == null ? now : closedAt;
    Duration bruto = Duration.between(openedAt, fim);
    if (bruto.isNegative()) {
      return Duration.ZERO;
    }
    Duration pausa = pausedTime(openedAt, fim, actions);
    Duration liquido = bruto.minus(pausa);
    return liquido.isNegative() ? Duration.ZERO : liquido;
  }

  /** Soma das janelas de espera comprováveis, recortadas ao intervalo de vida do caso. */
  public static Duration pausedTime(Instant openedAt, Instant fim, List<CaseAction> actions) {
    List<CaseAction> ordenadas =
        actions.stream()
            .filter(
                a ->
                    a.type() == CaseActionType.DOCUMENT_REQUESTED
                        || a.type() == CaseActionType.DOCUMENT_RECEIVED)
            .sorted(java.util.Comparator.comparing(CaseAction::occurredAt))
            .toList();

    Duration total = Duration.ZERO;
    Instant aberturaDaEspera = null;
    Instant fimDaUltimaPausa = null;

    for (CaseAction action : ordenadas) {
      if (action.type() == CaseActionType.DOCUMENT_REQUESTED) {
        // Pedido repetido antes de receber não abre uma segunda janela: a espera é a mesma.
        if (aberturaDaEspera == null) {
          aberturaDaEspera = action.occurredAt();
        }
        continue;
      }
      if (aberturaDaEspera == null) {
        // Recebimento sem pedido: não há espera provável a descontar.
        continue;
      }
      Instant inicio = maxInstant(aberturaDaEspera, openedAt);
      Instant termino = minInstant(action.occurredAt(), fim);
      // Sobreposição com a pausa anterior não é somada duas vezes.
      if (fimDaUltimaPausa != null && inicio.isBefore(fimDaUltimaPausa)) {
        inicio = fimDaUltimaPausa;
      }
      if (termino.isAfter(inicio)) {
        total = total.plus(Duration.between(inicio, termino));
        fimDaUltimaPausa = termino;
      }
      aberturaDaEspera = null;
    }
    // Espera ainda aberta (pediu e não recebeu) NÃO é descontada — ver Javadoc da classe.
    return total;
  }

  private static Instant maxInstant(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  private static Instant minInstant(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
