package com.barrier.riskengine.screening.client.interfaces;

/**
 * Especialização de {@link WatchlistProvider} para fontes de mídia negativa (busca por
 * nome + termos sensíveis: lavagem de dinheiro, corrupção, fraude, tráfico, terrorismo,
 * pirâmide financeira). Mesma interface de busca — o motor de screening já agrega qualquer
 * {@link WatchlistProvider}; esta marca só documenta a intenção e facilita achar/trocar as
 * implementações (BigBoost/LexisNexis/Dow Jones ficam atrás dela no futuro).
 */
public interface NegativeMediaProvider extends WatchlistProvider {}
