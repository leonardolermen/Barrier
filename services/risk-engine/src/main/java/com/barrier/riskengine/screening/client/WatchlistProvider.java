package com.barrier.riskengine.screening.client;

import java.util.List;

/**
 * Integração com uma fonte de listas restritivas (OFAC, ONU, CGU, base de PEP, ...), atrás de
 * interface. Retorna os registros que casam com a consulta; lista vazia = nada encontrado.
 */
public interface WatchlistProvider {

  List<WatchlistEntry> search(WatchlistQuery query);

  /**
   * Consulta várias partes de uma vez (titular + sócios + representante legal).
   *
   * <p>Existe para que o custo de uma avaliação de PJ não seja multiplicado pelo tamanho do quadro
   * societário. O default — uma busca por parte — está certo para providers indexados por
   * documento; quem varre a base inteira por nome deve sobrescrever e varrer <b>uma vez só</b>,
   * comparando todas as partes na mesma passada.
   */
  default List<WatchlistEntry> searchAll(List<WatchlistQuery> queries) {
    return queries.stream().flatMap(query -> search(query).stream()).toList();
  }

  /** Nome curto da fonte, para auditoria. */
  String name();
}
