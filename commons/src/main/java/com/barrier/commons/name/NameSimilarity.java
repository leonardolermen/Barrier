package com.barrier.commons.name;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Compara o nome <b>informado pelo cliente</b> com o nome <b>oficial</b> de uma fonte autoritativa
 * (bureau/Receita), respondendo se são plausivelmente a mesma entidade.
 *
 * <p>Não é igualdade de string: quem digita um cadastro abrevia, omite nome do meio, troca acento,
 * escreve "S/A" em vez de "S.A." e quase nunca repete a razão social inteira. Três critérios, em
 * ordem de custo:
 *
 * <ol>
 *   <li>igualdade após normalização;
 *   <li><b>cada token</b> significativo do informado tem contraparte no oficial — igual ou dentro
 *       do limiar de Jaro-Winkler, para absorver erro de digitação. "JOAO SILVA" casa com "JOAO
 *       PEREIRA DA SILVA"; "ACME COMERCIO" casa com "ACME COMERCIO DE ALIMENTOS LTDA".
 * </ol>
 *
 * <p>A direção importa: informar <i>menos</i> que o oficial é normal (abreviação), informar um
 * token que o oficial não tem, não.
 *
 * <p><b>Por que token a token, e não Jaro-Winkler na string inteira:</b> o Jaro-Winkler premia
 * prefixos iguais, então dois nomes que só compartilham o primeiro nome — "CARLOS EDUARDO NUNES" e
 * "CARLOS ROBERTO MENDES" — passam de 0.85 e seriam considerados a mesma pessoa. É precisamente o
 * falso negativo que esta comparação existe para impedir. Comparar token a token também torna a
 * checagem imune à ordem ("SILVA, JOAO" vs "JOAO SILVA").
 *
 * <p>Termos sem poder discriminante (conectivos e formas societárias) são descartados antes: senão
 * "LTDA" contaria como evidência de que dois nomes coincidem. E um único token significativo não
 * confirma identidade contra um nome oficial mais longo — só "SILVA" não identifica ninguém dentro
 * de "JOAO PEREIRA DA SILVA".
 */
public final class NameSimilarity {

  /**
   * Conectivos de nome de pessoa e formas societárias/porte de PJ. Aparecem em quase todo nome e
   * por isso não distinguem ninguém.
   */
  private static final Set<String> NOISE =
      Set.of(
          "DE", "DA", "DO", "DAS", "DOS", "E", "DI", "DU", "LA", "LE",
          "LTDA", "LIMITADA", "ME", "EPP", "EIRELI", "SA", "S", "A", "CIA", "MEI");

  /** Mínimo de tokens significativos para o informado identificar um nome oficial composto. */
  private static final int MIN_TOKENS = 2;

  private NameSimilarity() {}

  /**
   * Indica se o nome informado é compatível com o oficial.
   *
   * @param informed nome como veio na requisição
   * @param official nome como veio da fonte autoritativa
   * @param threshold similaridade mínima (0.0–1.0) para o critério fuzzy
   * @return {@code false} quando qualquer um dos dois está ausente — não se confirma identidade com
   *     dado que não existe; cabe ao chamador decidir o que fazer com isso
   */
  public static boolean matches(String informed, String official, double threshold) {
    String a = NameNormalizer.normalize(informed);
    String b = NameNormalizer.normalize(official);
    if (a.isBlank() || b.isBlank()) {
      return false;
    }
    if (a.equals(b)) {
      return true;
    }

    Set<String> informedTokens = significantTokens(a);
    Set<String> officialTokens = significantTokens(b);
    if (informedTokens.isEmpty() || officialTokens.isEmpty()) {
      return false;
    }
    // Um só token informado contra um nome oficial composto é evidência fraca demais.
    if (informedTokens.size() < MIN_TOKENS && officialTokens.size() > 1) {
      return false;
    }
    return informedTokens.stream().allMatch(token -> hasCounterpart(token, officialTokens, threshold));
  }

  /**
   * O quão bem o informado casou, para a evidência da decisão: a similaridade do <b>token pior
   * casado</b> — a mesma medida em que a decisão se apoia. Uma média esconderia justamente o token
   * que não bateu.
   */
  public static double similarity(String informed, String official) {
    Set<String> informedTokens = significantTokens(NameNormalizer.normalize(informed));
    Set<String> officialTokens = significantTokens(NameNormalizer.normalize(official));
    if (informedTokens.isEmpty() || officialTokens.isEmpty()) {
      return 0.0;
    }
    return informedTokens.stream().mapToDouble(t -> bestSimilarity(t, officialTokens)).min().orElse(0.0);
  }

  private static boolean hasCounterpart(String token, Set<String> officialTokens, double threshold) {
    return bestSimilarity(token, officialTokens) >= threshold;
  }

  private static double bestSimilarity(String token, Set<String> officialTokens) {
    return officialTokens.stream()
        .mapToDouble(official -> JaroWinkler.similarity(token, official))
        .max()
        .orElse(0.0);
  }

  private static Set<String> significantTokens(String normalized) {
    return Arrays.stream(normalized.split(" "))
        .filter(token -> !token.isBlank() && !NOISE.contains(token))
        .collect(LinkedHashSet::new, Set::add, Set::addAll);
  }
}
