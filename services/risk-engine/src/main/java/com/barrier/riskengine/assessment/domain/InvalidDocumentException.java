package com.barrier.riskengine.assessment.domain;

/** Lançada quando um documento (CPF/CNPJ) é inválido. */
public class InvalidDocumentException extends RuntimeException {

  public InvalidDocumentException(String message) {
    super(message);
  }
}
