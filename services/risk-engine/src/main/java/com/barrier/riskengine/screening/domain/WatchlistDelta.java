package com.barrier.riskengine.screening.domain;

import java.util.List;

/**
 * O que mudou numa importação de lista restritiva, em relação ao que já estava na base.
 *
 * <p>Existe para o monitoramento contínuo: reavaliar todos os clientes a cada importação diária é
 * caro (cada avaliação consulta bureau pago) e afogaria a fila de análise; o que interessa é quem
 * passou a estar numa lista desde ontem.
 *
 * @param added entradas que não existiam na importação anterior desta fonte
 * @param baseline importação sobre base vazia — a fonte não tinha nenhuma entrada antes. Aqui
 *     <b>tudo</b> é "novo" sem que nada tenha mudado no mundo: é a primeira carga, ou uma carga
 *     depois de a fonte ter falhado e sido esvaziada. Disparar rescreening nesse caso reavaliaria
 *     a base inteira de clientes por um evento que não é um fato sobre eles.
 */
public record WatchlistDelta(List<WatchlistRecord> added, boolean baseline) {

  public WatchlistDelta {
    added = List.copyOf(added);
  }

  /** Primeira carga desta fonte: não há "novo" que signifique mudança no mundo. */
  public static WatchlistDelta firstLoad() {
    return new WatchlistDelta(List.of(), true);
  }

  public static WatchlistDelta of(List<WatchlistRecord> added) {
    return new WatchlistDelta(added, false);
  }

  public boolean isEmpty() {
    return added.isEmpty();
  }
}
