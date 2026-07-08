package com.barrier.riskengine.screening.watchlist;

/**
 * Similaridade Jaro-Winkler entre duas strings (0.0 a 1.0). Boa para nomes próprios: tolera
 * transposições e variações e premia prefixos iguais. Implementação local (sem dependência).
 */
public final class JaroWinkler {

  private static final double PREFIX_SCALE = 0.1;
  private static final int MAX_PREFIX = 4;

  private JaroWinkler() {}

  public static double similarity(String a, String b) {
    if (a.equals(b)) {
      return 1.0;
    }
    if (a.isEmpty() || b.isEmpty()) {
      return 0.0;
    }

    double jaro = jaro(a, b);
    int prefix = 0;
    int max = Math.min(MAX_PREFIX, Math.min(a.length(), b.length()));
    while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) {
      prefix++;
    }
    return jaro + prefix * PREFIX_SCALE * (1.0 - jaro);
  }

  private static double jaro(String a, String b) {
    int matchDistance = Math.max(a.length(), b.length()) / 2 - 1;
    boolean[] aMatches = new boolean[a.length()];
    boolean[] bMatches = new boolean[b.length()];

    int matches = 0;
    for (int i = 0; i < a.length(); i++) {
      int start = Math.max(0, i - matchDistance);
      int end = Math.min(i + matchDistance + 1, b.length());
      for (int j = start; j < end; j++) {
        if (bMatches[j] || a.charAt(i) != b.charAt(j)) {
          continue;
        }
        aMatches[i] = true;
        bMatches[j] = true;
        matches++;
        break;
      }
    }
    if (matches == 0) {
      return 0.0;
    }

    double transpositions = 0;
    int k = 0;
    for (int i = 0; i < a.length(); i++) {
      if (!aMatches[i]) {
        continue;
      }
      while (!bMatches[k]) {
        k++;
      }
      if (a.charAt(i) != b.charAt(k)) {
        transpositions++;
      }
      k++;
    }
    transpositions /= 2;

    double m = matches;
    return (m / a.length() + m / b.length() + (m - transpositions) / m) / 3.0;
  }
}
