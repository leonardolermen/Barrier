package com.barrier.riskengine.screening.repository;

import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.util.List;

/** Repositório de domínio das entradas de watchlist ingeridas. */
public interface WatchlistEntryRepository {

  /** Substitui todas as entradas de uma fonte pelo novo lote (importação idempotente). */
  void replaceSource(String source, String version, List<WatchlistRecord> records);

  /** Entradas que casam com o documento (match exato por CPF/CNPJ). */
  List<WatchlistRecord> findByDocument(String document);
}
