package com.barrier.riskengine.tenant.domain;

/** Lançada quando o {@code X-Client-Id} está ausente, é desconhecido ou está inativo. */
public class UnknownTenantException extends RuntimeException {

  public UnknownTenantException(String message) {
    super(message);
  }
}
