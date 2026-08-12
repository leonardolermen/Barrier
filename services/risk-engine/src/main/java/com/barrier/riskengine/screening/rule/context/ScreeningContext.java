package com.barrier.riskengine.screening.rule.context;

import com.barrier.riskengine.screening.client.WatchlistEntry;
import com.barrier.riskengine.screening.client.WatchlistQuery;
import java.util.List;

/** Insumo das regras de screening: a consulta e os registros trazidos das listas. */
public record ScreeningContext(WatchlistQuery query, List<WatchlistEntry> entries) {}
