package com.barrier.riskengine.assurance.domain;

/**
 * Kill switch da frente de assurance desligado ({@code barrier.assurance.enabled=false}).
 *
 * <p>409, não 500: não há erro nenhum: a capacidade está desligada por decisão operacional, e o
 * parceiro precisa distinguir isso de "falta documentoscopia" ({@code
 * DocumentGateNotSatisfiedException}) e de uma falha real. Eram todas {@code
 * IllegalStateException} antes, indistinguíveis pelo tipo.
 */
public class AssuranceDisabledException extends RuntimeException {

  public AssuranceDisabledException(String message) {
    super(message);
  }
}
