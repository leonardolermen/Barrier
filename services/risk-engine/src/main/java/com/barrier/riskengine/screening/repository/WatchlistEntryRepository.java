package com.barrier.riskengine.screening.repository;

import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.util.List;

/** Repositório de domínio das entradas de watchlist ingeridas. */
public interface WatchlistEntryRepository {

  /** Substitui todas as entradas de uma fonte pelo novo lote (importação idempotente). */
  void replaceSource(String source, String version, List<WatchlistRecord> records);

  /** Entradas que casam com o documento (match exato por CPF/CNPJ). */
  List<WatchlistRecord> findByDocument(String document);

  /**
   * Todas as entradas com nome, para match fuzzy. Inclui as que também têm documento (ex.: uma
   * empresa sancionada é indexada por CNPJ E por razão social) — assim os dois caminhos de match
   * valem ao mesmo tempo, e um nome que casa não escapa só porque a entrada tem documento.
   */
  List<WatchlistRecord> findNameEntries();
}
