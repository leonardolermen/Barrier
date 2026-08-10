package com.barrier.commons.name;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tokens significativos de um nome, já normalizados — a forma comparável de um nome.
 *
 * <p>Existe como tipo próprio por dois motivos. O primeiro é reuso: a comparação token a token era
 * privada de {@link NameSimilarity} e por isso o screening de listas restritivas acabou usando
 * Jaro-Winkler sobre a string inteira, que é sensível à ordem e não sabe o que é ruído — o
 * comparador certo existia no mesmo repositório e não estava ao alcance de quem precisava dele.
 *
 * <p>O segundo é custo: o screening compara o nome consultado contra <b>toda</b> a base de listas.
 * Normalizar e tokenizar o lado consultado uma vez, em vez de a cada entrada, é a diferença entre
 * duas travessias por comparação e uma.
 */
public record NameTokens(Set<String> values) {

  /**
   * Conectivos de nome de pessoa e formas societárias/porte de PJ. Aparecem em quase todo nome e
   * por isso não distinguem ninguém.
   */
  private static final Set<String> NOISE =
      Set.of(
          "DE", "DA", "DO", "DAS", "DOS", "E", "DI", "DU", "LA", "LE",
          "LTDA", "LIMITADA", "ME", "EPP", "EIRELI", "SA", "S", "A", "CIA", "MEI");

  /** Mínimo de tokens significativos para um nome identificar outro mais longo. */
  private static final int MIN_TOKENS = 2;

  /**
   * Preserva a ordem de leitura do nome ({@code LinkedHashSet}, não {@code Set.copyOf}): não muda
   * nenhum resultado de comparação, mas mantém tokens em ordem estável para log e evidência.
   */
  public NameTokens {
    values = Collections.unmodifiableSet(new LinkedHashSet<>(values));
  }

  /** Normaliza e extrai os tokens significativos; nome nulo/vazio/só-ruído devolve conjunto vazio. */
  public static NameTokens of(String name) {
    String normalized = NameNormalizer.normalize(name);
    return new NameTokens(
        Arrays.stream(normalized.split(" "))
            .filter(token -> !token.isBlank() && !NOISE.contains(token))
            .collect(LinkedHashSet::new, Set::add, Set::addAll));
  }

  public boolean isEmpty() {
    return values.isEmpty();
  }

  public int size() {
    return values.size();
  }

  /**
   * Indica se <b>cada</b> token deste nome tem contraparte em {@code other} — igual ou dentro do
   * limiar de Jaro-Winkler, para absorver erro de digitação.
   *
   * <p>É uma relação direcional de subconjunto: "JOAO SILVA" é coberto por "JOAO PEREIRA DA SILVA",
   * não o contrário. Um único token significativo não cobre um nome composto — só "SILVA" não
   * identifica ninguém dentro de "JOAO PEREIRA DA SILVA".
   */
  public boolean coveredBy(NameTokens other, double threshold) {
    if (isEmpty() || other.isEmpty()) {
      return false;
    }
    if (size() < MIN_TOKENS && other.size() > 1) {
      return false;
    }
    return values.stream().allMatch(token -> other.bestSimilarityOf(token) >= threshold);
  }

  /**
   * Similaridade do token pior casado deste nome contra {@code other} — a mesma medida em que a
   * decisão se apoia. Uma média esconderia justamente o token que não bateu.
   */
  public double weakestMatchIn(NameTokens other) {
    if (isEmpty() || other.isEmpty()) {
      return 0.0;
    }
    return values.stream().mapToDouble(other::bestSimilarityOf).min().orElse(0.0);
  }

  /** Melhor similaridade entre {@code token} e algum token deste nome. */
  private double bestSimilarityOf(String token) {
    return values.stream().mapToDouble(value -> JaroWinkler.similarity(token, value)).max().orElse(0.0);
  }
}
