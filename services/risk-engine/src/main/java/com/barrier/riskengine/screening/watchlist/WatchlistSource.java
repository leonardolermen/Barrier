package com.barrier.riskengine.screening.watchlist;

/**
 * Fonte de uma lista restritiva (Strategy). Cada fonte sabe se obter (arquivo local, download
 * HTTP, etc.) e devolver as entradas. Adicionar uma lista = adicionar uma implementação.
 */
public interface WatchlistSource {

  /** Nome curto da fonte (ex.: CEIS, OFAC) — usado como chave na tabela. */
  String source();

  /** Busca e parseia a lista. Pode lançar em caso de falha (o importador isola por fonte). */
  WatchlistBatch fetch();
}
