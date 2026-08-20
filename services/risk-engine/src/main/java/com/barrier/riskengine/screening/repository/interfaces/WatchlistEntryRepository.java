package com.barrier.riskengine.screening.repository.interfaces;

import com.barrier.riskengine.screening.domain.WatchlistDelta;
import com.barrier.riskengine.screening.domain.WatchlistRecord;
import java.util.List;
import java.util.Set;
import java.util.Map;

/** Repositório de domínio das entradas de watchlist ingeridas. */
public interface WatchlistEntryRepository {

  /**
   * Substitui todas as entradas de uma fonte pelo novo lote (importação idempotente) e devolve o
   * que mudou.
   *
   * <p>O delta é calculado <b>dentro</b> da substituição porque é o único ponto onde as duas
   * versões da lista coexistem: logo depois do {@code DELETE} a anterior deixou de existir, e
   * reconstruí-la exigiria versionar a tabela inteira.
   */
  WatchlistDelta replaceSource(String source, String version, List<WatchlistRecord> records);

  /** Entradas que casam com o documento (match exato por CPF/CNPJ). */
  List<WatchlistRecord> findByDocument(String document);

  /**
   * Todas as entradas com nome, para match fuzzy. Inclui as que também têm documento (ex.: uma
   * empresa sancionada é indexada por CNPJ E por razão social) — assim os dois caminhos de match
   * valem ao mesmo tempo, e um nome que casa não escapa só porque a entrada tem documento.
   */
  List<WatchlistRecord> findNameEntries();

  /**
   * Candidatos a match por nome, filtrados por indice de trigramas.
   *
   * <p>Substitui o carregamento da base inteira no caminho quente. O parametro e um LIMIAR DE
   * BLOCKING, nao de decisao: quem decide continua sendo a cobertura token a token do provider.
   * Frouxo de proposito — candidato a mais custa uma comparacao em memoria, candidato a menos e um
   * sancionado nao encontrado.
   */
  List<WatchlistRecord> findNameCandidates(Set<String> tokens, double blockingThreshold);

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
