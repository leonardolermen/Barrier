package com.barrier.riskengine.screening.watchlist;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Trava de segurança operacional: {@code CeisWatchlistSource}, {@code CnepWatchlistSource} e
 * {@code OfacWatchlistSource} ficam desligadas por padrão ({@code barrier.watchlist.*.enabled} =
 * {@code false}) para não depender de rede externa em dev/testes. Sem esta trava, um deploy de
 * produção que esqueça de habilitar essas flags sobe silenciosamente rodando só com o CSV seed
 * — um gap de compliance invisível.
 *
 * <p>Falha rápido (não sobe) se o profile {@code prod} estiver ativo e a única fonte de
 * watchlist presente for a {@code SEED}. Em outros profiles, apenas avisa.
 */
@Component
public class WatchlistReadinessGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(WatchlistReadinessGuard.class);
  private static final String SEED_SOURCE = "SEED";
  private static final String PROD_PROFILE = "prod";

  private final List<WatchlistSource> sources;
  private final Environment environment;

  public WatchlistReadinessGuard(List<WatchlistSource> sources, Environment environment) {
    this.sources = sources;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> active = sources.stream().map(WatchlistSource::source).toList();
    boolean onlySeed = active.size() == 1 && active.contains(SEED_SOURCE);
    if (!onlySeed) {
      return;
    }
    if (Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
      throw new IllegalStateException(
          "Profile 'prod' ativo com apenas a watchlist SEED habilitada. Habilite "
              + "barrier.watchlist.cgu.enabled e barrier.watchlist.ofac.enabled antes de subir "
              + "em produção (ver docs/adr/0013-watchlist-fontes-producao.md).");
    }
    log.warn(
        "Rodando apenas com a watchlist SEED ({}); não usar em produção sem habilitar CGU/OFAC.",
        SEED_SOURCE);
  }
}
