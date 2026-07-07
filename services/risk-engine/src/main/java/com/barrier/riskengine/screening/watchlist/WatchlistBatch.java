package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.util.List;

/**
 * Um lote importado de uma fonte de watchlist.
 *
 * @param version versão/data da lista (para auditoria)
 * @param records entradas da lista
 */
public record WatchlistBatch(String version, List<WatchlistRecord> records) {}
