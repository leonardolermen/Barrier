package com.barrier.commons.name;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normaliza nomes para comparação fuzzy: maiúsculas, sem acentos, sem pontuação, espaços
 * colapsados. Determinístico e sem estado.
 */
public final class NameNormalizer {

  private NameNormalizer() {}

  public static String normalize(String name) {
    if (name == null) {
      return "";
    }
    String noAccents =
        Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    return noAccents
        .toUpperCase(Locale.ROOT)
        .replaceAll("[^A-Z0-9 ]", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }
}
