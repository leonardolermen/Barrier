package com.barrier.riskengine.screening.watchlist.interfaces;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import com.barrier.riskengine.screening.watchlist.WatchlistBatch;
import com.barrier.riskengine.screening.watchlist.WatchlistReadinessGuard;

import java.util.Set;

/**
 * Fonte de uma lista restritiva (Strategy). Cada fonte sabe se obter (arquivo local, download
 * HTTP, etc.) e devolver as entradas. Adicionar uma lista = adicionar uma implementação.
 */
public interface WatchlistSource {

  /** Nome curto da fonte (ex.: CEIS, OFAC, PEP) — usado como chave na tabela. */
  String source();

  /** Busca e parseia a lista. Pode lançar em caso de falha (o importador isola por fonte). */
  WatchlistBatch fetch();

  /**
   * Categorias que esta fonte produz. Declarado no tipo (e não descoberto lendo a tabela) para que
   * o {@link WatchlistReadinessGuard} possa verificar, <b>na subida</b>, se a cobertura obrigatória
   * está presente — antes de qualquer avaliação ser decidida sem ela.
   */
  Set<MatchType> provides();
}
