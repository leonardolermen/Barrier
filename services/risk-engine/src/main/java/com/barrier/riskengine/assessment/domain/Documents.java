package com.barrier.riskengine.assessment.domain;

/** Normalização/validação de documento em um só lugar (usado pelo agregado e pelo subject). */
public final class Documents {

  private Documents() {}

  /**
   * Valida e retorna apenas os dígitos do documento.
   *
   * @throws InvalidDocumentException se o CPF/CNPJ for inválido
   */
  public static String normalize(DocumentType type, String raw) {
    return switch (type) {
      case CPF -> new Cpf(raw).digits();
      case CNPJ -> new Cnpj(raw).digits();
    };
  }
}
