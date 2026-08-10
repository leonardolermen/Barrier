package com.barrier.commons.name;

/**
 * Compara nomes decidindo se são plausivelmente a mesma entidade. Duas relações, com semânticas
 * deliberadamente diferentes porque respondem a perguntas diferentes:
 *
 * <ul>
 *   <li>{@link #matches} — <b>direcional</b>, para confrontar o nome informado pelo cliente com o
 *       nome oficial de uma fonte autoritativa (bureau/Receita). Informar <i>menos</i> que o
 *       oficial é normal (abreviação); informar um token que o oficial não tem, não.
 *   <li>{@link #matchesEitherWay} — <b>simétrica</b>, para screening em listas restritivas. Ali não
 *       existe lado "oficial": a lista publica o nome no formato dela (a OFAC usa
 *       {@code SOBRENOME, Nome}), com mais ou menos tokens que o cadastro, e exigir uma direção
 *       específica é o mesmo que não procurar.
 * </ul>
 *
 * <p>Não é igualdade de string: quem digita um cadastro abrevia, omite nome do meio, troca acento,
 * escreve "S/A" em vez de "S.A." e quase nunca repete a razão social inteira. A comparação é token
 * a token ({@link NameTokens}), com Jaro-Winkler por token para absorver erro de digitação.
 *
 * <p><b>Por que token a token, e não Jaro-Winkler na string inteira:</b> o Jaro-Winkler premia
 * prefixos iguais, então dois nomes que só compartilham o primeiro nome — "CARLOS EDUARDO NUNES" e
 * "CARLOS ROBERTO MENDES" — passam de 0.85 e seriam considerados a mesma pessoa. Na direção oposta,
 * o mesmo prêmio de prefixo faz "JOSE ANTONIO DA SILVA" <i>não</i> casar com "SILVA, JOSE ANTONIO",
 * que é a mesma pessoa com a ordem invertida. Comparar token a token elimina os dois erros.
 */
public final class NameSimilarity {

  private NameSimilarity() {}

  /**
   * Indica se o nome informado é compatível com o oficial (relação direcional de subconjunto).
   *
   * @param informed nome como veio na requisição
   * @param official nome como veio da fonte autoritativa
   * @param threshold similaridade mínima (0.0–1.0) por token
   * @return {@code false} quando qualquer um dos dois está ausente — não se confirma identidade com
   *     dado que não existe; cabe ao chamador decidir o que fazer com isso
   */
  public static boolean matches(String informed, String official, double threshold) {
    return NameTokens.of(informed).coveredBy(NameTokens.of(official), threshold);
  }

  /**
   * Indica se dois nomes são plausivelmente da mesma entidade, em qualquer direção.
   *
   * <p>Usado no screening de listas restritivas, onde a assimetria de custo é o oposto da
   * verificação de cadastro: um falso positivo custa minutos de um analista; um falso negativo é
   * relacionamento com sancionado. Por isso basta que <b>um</b> dos nomes cubra o outro.
   */
  public static boolean matchesEitherWay(String a, String b, double threshold) {
    NameTokens left = NameTokens.of(a);
    NameTokens right = NameTokens.of(b);
    return left.coveredBy(right, threshold) || right.coveredBy(left, threshold);
  }

  /**
   * O quão bem o informado casou, para a evidência da decisão: a similaridade do <b>token pior
   * casado</b> — a mesma medida em que a decisão se apoia.
   */
  public static double similarity(String informed, String official) {
    return NameTokens.of(informed).weakestMatchIn(NameTokens.of(official));
  }

  /** Versão simétrica de {@link #similarity}: a melhor das duas direções. */
  public static double similarityEitherWay(String a, String b) {
    NameTokens left = NameTokens.of(a);
    NameTokens right = NameTokens.of(b);
    return Math.max(left.weakestMatchIn(right), right.weakestMatchIn(left));
  }
}
