package com.barrier.riskengine.screening.client.interfaces;

/**
 * Especialização de {@link WatchlistProvider} para fontes de mídia negativa (busca por
 * nome + termos sensíveis: lavagem de dinheiro, corrupção, fraude, tráfico, terrorismo,
 * pirâmide financeira). Mesma interface de busca — o motor de screening já agrega qualquer
 * {@link WatchlistProvider}; esta marca só documenta a intenção e facilita achar/trocar as
 * implementações (BigBoost/LexisNexis/Dow Jones ficam atrás dela no futuro).
 */
public interface NegativeMediaProvider extends WatchlistProvider {

  /**
   * Indica se este provider é uma fonte contratada de verdade, e não o stub de dev/teste.
   *
   * <p>Mesmo conceito de {@link com.barrier.riskengine.identity.client.BureauProvider#authoritative()}:
   * separa "provider fictício sempre presente" de "provider que representa um controle real
   * contratado". Existe para o {@code ScreeningCoverageRiskRule} distinguir "mídia negativa nunca
   * foi contratada" (não pontuar — alarme por avaliação não informa nada quando vale para 100% da
   * base) de "existe provedor contratado e a cobertura não está confirmada" (pontuar — controle
   * que deveria estar rodando e não está, mesmo tratamento de sanção e PEP).
   */
  default boolean authoritative() {
    return true;
  }
}
