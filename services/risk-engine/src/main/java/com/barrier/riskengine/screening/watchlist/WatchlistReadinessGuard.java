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
 * <p>Verifica duas coisas em {@code prod}, ambas obrigatórias e barram a subida:
 *
 * <ul>
 *   <li>existe alguma fonte real além da {@code SEED};
 *   <li>a cobertura contempla <b>sanções e PEP</b>. A checagem de PEP existe porque durante muito
 *       tempo nenhuma fonte produzia {@link MatchType#PEP} — CEIS, CNEP e OFAC são todas de sanção
 *       — e a {@code PepRiskRule} ficava inerte, dando a impressão de que a exigência de due
 *       diligence reforçada da Circular BCB 3.978 estava coberta quando não estava.
 * </ul>
 *
 * <p><b>Mídia negativa ({@code ADVERSE_MEDIA}) só avisa, no padrão do {@link
 * com.barrier.riskengine.identity.client.CnpjBureauReadinessGuard} para a BrasilAPI como único
 * bureau de PJ.</b> Não existe hoje provedor de mídia negativa contratado — o único é o
 * {@code StubNegativeMediaProvider}, que não é {@code WatchlistSource} e nunca conta para
 * cobertura em produção real. Barrar a subida por causa disso trocaria um fail-open (aprova sem
 * checar mídia negativa) por indisponibilidade total da plataforma por falta de um provedor que
 * ninguém contratou ainda — mais forte do que o problema justifica.
 * {@code ScreeningCoverageRiskRule} já fecha o fail-open de verdade: sem cobertura de
 * {@code ADVERSE_MEDIA}, a avaliação não aprova em silêncio, ela vai para revisão humana.
 *
 * <p>Em outros profiles, tudo é só aviso.
 */
@Component
public class WatchlistReadinessGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(WatchlistReadinessGuard.class);
  private static final String SEED_SOURCE = "SEED";
  private static final String PROD_PROFILE = "prod";
  private static final Set<MatchType> REQUIRED = Set.of(MatchType.SANCTION, MatchType.PEP);
  private static final Set<MatchType> WARN_ONLY = Set.of(MatchType.ADVERSE_MEDIA);

  private final List<WatchlistSource> sources;
  private final Environment environment;

  public WatchlistReadinessGuard(List<WatchlistSource> sources, Environment environment) {
    this.sources = sources;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean prod = Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
    List<String> problems = problems();
    if (!problems.isEmpty()) {
      if (prod) {
        throw new IllegalStateException(
            "Profile 'prod' ativo com cobertura de watchlist incompleta: "
                + String.join("; ", problems)
                + ". Habilite barrier.watchlist.cgu.enabled e barrier.watchlist.ofac.enabled antes de "
                + "subir em produção (ver docs/adr/0013-watchlist-fontes-producao.md).");
      }
      log.warn("Cobertura de watchlist incompleta: {}. Não usar em produção.", String.join("; ", problems));
    }

    List<String> warnings = warnOnlyProblems();
    if (!warnings.isEmpty()) {
      log.warn(
          "Cobertura de watchlist sem fonte para {} — sem provedor contratado, "
              + "ScreeningCoverageRiskRule força revisão em vez de aprovar em silêncio; não "
              + "derruba a subida porque hoje não existe provedor de mídia negativa disponível "
              + "para contratar.",
          String.join(", ", warnings));
    }
  }

  /**
   * A {@code SEED} não conta para cobertura: é um CSV de exemplo com duas linhas, empacotado no
   * jar. Contá-la faria a trava passar exatamente no cenário que ela existe para barrar.
   */
  private List<String> problems() {
    List<WatchlistSource> real = realSources();
    if (real.isEmpty()) {
      return List.of("nenhuma fonte real habilitada (apenas a SEED)");
    }

    Set<MatchType> covered = coverage(real);
    List<String> problems = new ArrayList<>();
    for (MatchType required : REQUIRED) {
      if (!covered.contains(required)) {
        problems.add("nenhuma fonte de " + required);
      }
    }
    return problems;
  }

  /** Categorias que só avisam (ver Javadoc da classe) — nunca barram a subida em prod. */
  private List<String> warnOnlyProblems() {
    Set<MatchType> covered = coverage(realSources());
    List<String> warnings = new ArrayList<>();
    for (MatchType category : WARN_ONLY) {
      if (!covered.contains(category)) {
        warnings.add(category.name());
      }
    }
    return warnings;
  }

  private List<WatchlistSource> realSources() {
    return sources.stream().filter(s -> !SEED_SOURCE.equals(s.source())).toList();
  }

  private static Set<MatchType> coverage(List<WatchlistSource> real) {
    return real.stream()
        .flatMap(s -> s.provides().stream())
        .collect(java.util.stream.Collectors.toSet());
  }
}
