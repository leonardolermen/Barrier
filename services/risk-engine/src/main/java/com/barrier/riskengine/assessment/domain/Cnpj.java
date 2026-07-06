package com.barrier.riskengine.assessment.domain;

/** CNPJ válido (dígitos verificadores conferidos). Armazenado apenas com os 14 dígitos. */
public record Cnpj(String digits) {

  private static final int[] WEIGHTS_D1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
  private static final int[] WEIGHTS_D2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

  public Cnpj {
    if (digits == null) {
      throw new InvalidDocumentException("CNPJ nulo");
    }
    String only = digits.replaceAll("\\D", "");
    if (!isValid(only)) {
      throw new InvalidDocumentException("CNPJ inválido");
    }
    digits = only;
  }

  private static boolean isValid(String cnpj) {
    if (cnpj.length() != 14 || cnpj.chars().distinct().count() == 1) {
      return false;
    }
    int d1 = checkDigit(cnpj, WEIGHTS_D1);
    int d2 = checkDigit(cnpj, WEIGHTS_D2);
    return d1 == (cnpj.charAt(12) - '0') && d2 == (cnpj.charAt(13) - '0');
  }

  private static int checkDigit(String cnpj, int[] weights) {
    int sum = 0;
    for (int i = 0; i < weights.length; i++) {
      sum += (cnpj.charAt(i) - '0') * weights[i];
    }
    int mod = sum % 11;
    return mod < 2 ? 0 : 11 - mod;
  }

  /** Retorna o CNPJ mascarado para log/exposição. */
  public String masked() {
    return "**.***.***/" + digits.substring(8, 12) + "-**";
  }
}
