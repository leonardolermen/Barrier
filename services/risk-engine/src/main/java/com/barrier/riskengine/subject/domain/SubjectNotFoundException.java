package com.barrier.riskengine.subject.domain;

/**
 * Lançada quando o subject não existe OU o tenant não tem vínculo com ele. Os dois casos são
 * indistinguíveis de propósito (não vazar a existência de um cliente de outra empresa).
 */
public class SubjectNotFoundException extends RuntimeException {

  public SubjectNotFoundException(String message) {
    super(message);
  }
}
