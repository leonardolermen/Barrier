package com.barrier.riskengine.screening.client;

import java.util.List;

/**
 * Integração com uma fonte de listas restritivas (OFAC, ONU, CGU, base de PEP, ...), atrás de
 * interface. Retorna os registros que casam com a consulta; lista vazia = nada encontrado.
 */
public interface WatchlistProvider {

  List<WatchlistEntry> search(WatchlistQuery query);

  /** Nome curto da fonte, para auditoria. */
  String name();
}
