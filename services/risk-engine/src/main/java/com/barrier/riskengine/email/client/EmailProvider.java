package com.barrier.riskengine.email.client;

/** Consulta de metadados de um email (domínio descartável, etc.), atrás de interface. */
public interface EmailProvider {

  /** Nunca {@code null}; {@link EmailLookup#UNKNOWN} quando não há dado para o email. */
  EmailLookup lookup(String email);
}
