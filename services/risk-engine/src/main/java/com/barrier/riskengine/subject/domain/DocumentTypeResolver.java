package com.barrier.riskengine.subject.domain;

/**
 * Resolve o tipo de documento (CPF/CNPJ) a partir do texto recebido na URL — mesma regra usada
 * hoje em três controllers diferentes ({@code SubjectController}, {@code
 * SubjectProfileController}, {@code AssuranceController}). Existia triplicada; centralizar evita
 * que um deles divirja (ex.: aceitar um tamanho que os outros recusam).
 */
public final class DocumentTypeResolver {

  private DocumentTypeResolver() {}

  /** Documento resolvido: só dígitos e o tipo inferido pelo tamanho. */
  public record Resolved(String documentType, String digits) {}

  /**
   * @throws IllegalArgumentException se não tiver 11 (CPF) nem 14 (CNPJ) dígitos — vira 400 pelo
   *     {@code ProblemExceptionHandler}.
   */
  public static Resolved resolve(String document) {
    String digits = document.replaceAll("\\D", "");
    String documentType =
        switch (digits.length()) {
          case 11 -> "CPF";
          case 14 -> "CNPJ";
          default -> throw new IllegalArgumentException("Documento inválido");
        };
    return new Resolved(documentType, digits);
  }
}
