package com.barrier.riskengine.screening.client;

import com.barrier.riskengine.screening.domain.ScreenedParty;

/**
 * Consulta a listas restritivas, para <b>uma</b> parte da relação.
 *
 * <p>Uma avaliação de PJ gera várias: o titular, cada sócio do QSA e o representante legal. O
 * {@code party} viaja junto porque o apontamento precisa saber a quem pertence — "sanção
 * encontrada" sem dizer se é a empresa ou um sócio não é acionável.
 */
public record WatchlistQuery(
    String documentType, String documentDigits, String name, ScreenedParty party) {

  /** Consulta do próprio titular da avaliação. */
  public WatchlistQuery(String documentType, String documentDigits, String name) {
    this(documentType, documentDigits, name, ScreenedParty.titular(name, documentDigits));
  }

  /** Consulta de uma parte relacionada; sócios do QSA não têm documento publicado. */
  public static WatchlistQuery of(String documentType, ScreenedParty party) {
    return new WatchlistQuery(documentType, party.document(), party.name(), party);
  }
}
