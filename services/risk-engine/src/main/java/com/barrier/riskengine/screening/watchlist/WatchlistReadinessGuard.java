package com.barrier.riskengine.screening.watchlist;

import com.barrier.riskengine.screening.domain.enums.MatchType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.barrier.riskengine.screening.watchlist.interfaces.WatchlistSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Trava de segurança operacional: as fontes reais ({@code CeisWatchlistSource},
 * {@code CnepWatchlistSource}, {@code PepWatchlistSource}, {@code OfacWatchlistSource}) ficam
 * desligadas por padrão ({@code barrier.watchlist.*.enabled} = {@code false}) para não depender de
 * rede externa em dev/testes. Sem esta trava, um deploy de produção que esqueça de habilitar essas
 * flags sobe silenciosamente rodando só com o CSV seed — um gap de compliance invisível.
 *
 * <p>Verifica duas coisas em {@code prod}, ambas obrigatórias:
 *
 * <ul>
 *   <li>existe alguma fonte real além da {@code SEED};
 *   <li>a cobertura contempla <b>sanções e PEP</b>. A checagem de PEP existe porque durante muito
 *       tempo nenhuma fonte produzia {@link MatchType#PEP} — CEIS, CNEP e OFAC são todas de sanção
 *       — e a {@code PepRiskRule} ficava inerte, dando a impressão de que a exigência de due
 *       diligence reforçada da Circular BCB 3.978 estava coberta quando não estava.
 * </ul>
 *
 * <p>Em outros profiles, apenas avisa.
 */
@Component
public class WatchlistReadinessGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(WatchlistReadinessGuard.class);
  private static final String SEED_SOURCE = "SEED";
  private static final String PROD_PROFILE = "prod";
  private static final Set<MatchType> REQUIRED = Set.of(MatchType.SANCTION, MatchType.PEP);

  private final List<WatchlistSource> sources;
  private final Environment environment;

  public WatchlistReadinessGuard(List<WatchlistSource> sources, Environment environment) {
    this.sources = sources;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> problems = problems();
    if (problems.isEmpty()) {
      return;
    }
    if (Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
      throw new IllegalStateException(
          "Profile 'prod' ativo com cobertura de watchlist incompleta: "
              + String.join("; ", problems)
              + ". Habilite barrier.watchlist.cgu.enabled e barrier.watchlist.ofac.enabled antes de "
              + "subir em produção (ver docs/adr/0013-watchlist-fontes-producao.md).");
    }
    log.warn("Cobertura de watchlist incompleta: {}. Não usar em produção.", String.join("; ", problems));
  }

  /**
   * A {@code SEED} não conta para cobertura: é um CSV de exemplo com duas linhas, empacotado no
   * jar. Contá-la faria a trava passar exatamente no cenário que ela existe para barrar.
   */
  private List<String> problems() {
    List<WatchlistSource> real =
        sources.stream().filter(s -> !SEED_SOURCE.equals(s.source())).toList();
    if (real.isEmpty()) {
      return List.of("nenhuma fonte real habilitada (apenas a SEED)");
    }

    Set<MatchType> covered =
        real.stream().flatMap(s -> s.provides().stream()).collect(java.util.stream.Collectors.toSet());
    List<String> problems = new ArrayList<>();
    for (MatchType required : REQUIRED) {
      if (!covered.contains(required)) {
        problems.add("nenhuma fonte de " + required);
      }
    }
    return problems;
  }
}
