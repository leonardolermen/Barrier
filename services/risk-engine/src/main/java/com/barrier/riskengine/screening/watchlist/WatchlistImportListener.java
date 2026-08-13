package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.WatchlistDelta;

/**
 * Notificação de que uma fonte de watchlist acabou de ser importada com sucesso.
 *
 * <p>Existe para inverter a dependência: o monitoramento contínuo é <b>consequência</b> da
 * importação, mas o screening não pode conhecer quem reage a ela. Antes desta interface,
 * {@code WatchlistImporter} chamava {@code RescreeningService} direto, fechando o ciclo
 * {@code screening → rescreening → assessment → screening} que a regra de arquitetura
 * {@code sem_ciclos_entre_modulos} proíbe. Quem reage implementa isto e depende da screening,
 * nunca o contrário.
 */
public interface WatchlistImportListener {

  /**
   * Chamado depois de a lista já estar gravada e utilizável. Uma exceção daqui não invalida a
   * importação — o {@code WatchlistImporter} isola cada listener.
   */
  void onImported(String source, String version, WatchlistDelta delta);
}
