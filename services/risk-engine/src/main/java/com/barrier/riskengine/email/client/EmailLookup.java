package com.barrier.riskengine.email.client;

/** Resultado de uma consulta de email. */
public record EmailLookup(boolean disposableDomain) {

  public static final EmailLookup UNKNOWN = new EmailLookup(false);
}
