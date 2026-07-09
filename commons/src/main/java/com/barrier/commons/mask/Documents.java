package com.barrier.commons.mask;

/**
 * Mascaramento de documento (CPF/CNPJ) para logs e respostas. Vive no {@code commons} para ser
 * reusável por qualquer módulo sem criar dependência entre contextos. Regra do projeto: nunca
 * expor CPF/CNPJ inteiro.
 */
public final class Documents {

  private Documents() {}

  /** Mantém só os 2 últimos dígitos ({@code ***...**35}); {@code null}/vazio vira {@code "?"}. */
  public static String mask(String digits) {
    if (digits == null || digits.isBlank()) {
      return "?";
    }
    if (digits.length() <= 2) {
      return digits;
    }
    return "*".repeat(digits.length() - 2) + digits.substring(digits.length() - 2);
  }
}
