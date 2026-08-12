package com.barrier.riskengine.screening.repository.interfaces;

import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.util.List;
import java.util.Map;

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

  /**
   * Versão da lista atualmente carregada, por fonte — o snapshot do que o screening consultou.
   *
   * <p>A {@code list_version} sempre existiu na tabela e nunca era copiada para a decisão, e a base
   * é substituída inteira todo dia ({@code replaceSource}). Passado um mês, não havia como
   * responder se um nome estava na lista <b>naquele dia</b>: a evidência de que o screening foi
   * feito existia, a de contra o quê não.
   */
  Map<String, String> sourceVersions();
}
