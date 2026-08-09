package com.barrier.riskengine.tenant.domain;

/** Lançada quando o tenant referenciado é desconhecido ou está inativo. */
public class UnknownTenantException extends RuntimeException {

  public UnknownTenantException(String message) {
    super(message);
  }
}
