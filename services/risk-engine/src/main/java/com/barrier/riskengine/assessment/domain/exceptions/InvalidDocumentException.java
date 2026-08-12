package com.barrier.riskengine.assessment.domain.exceptions;

/** Lançada quando um documento (CPF/CNPJ) é inválido. */
public class InvalidDocumentException extends RuntimeException {

  public InvalidDocumentException(String message) {
    super(message);
  }
}
