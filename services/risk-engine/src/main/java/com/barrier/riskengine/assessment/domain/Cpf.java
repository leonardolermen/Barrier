package com.barrier.riskengine.assessment.domain;

/**
 * CPF válido (dígitos verificadores conferidos). Armazenado apenas com os 11 dígitos.
 */
public record Cpf(String digits) {

  public Cpf {
    if (digits == null) {
      throw new InvalidDocumentException("CPF nulo");
    }
    String only = digits.replaceAll("\\D", "");
    if (!isValid(only)) {
      throw new InvalidDocumentException("CPF inválido");
    }
    digits = only;
  }

  private static boolean isValid(String cpf) {
    if (cpf.length() != 11 || cpf.chars().distinct().count() == 1) {
      return false;
    }
    int d1 = checkDigit(cpf, 9, 10);
    int d2 = checkDigit(cpf, 10, 11);
    return d1 == (cpf.charAt(9) - '0') && d2 == (cpf.charAt(10) - '0');
  }

  private static int checkDigit(String cpf, int length, int startWeight) {
    int sum = 0;
    int weight = startWeight;
    for (int i = 0; i < length; i++) {
      sum += (cpf.charAt(i) - '0') * weight--;
    }
    int mod = sum % 11;
    return mod < 2 ? 0 : 11 - mod;
  }

  /** Retorna o CPF mascarado para log/exposição: {@code ***.***.**9-00}. */
  public String masked() {
    return "***.***.**" + digits.substring(8, 9) + "-" + digits.substring(9);
  }
}
