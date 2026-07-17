package com.barrier.riskengine.credit.client;

/** Consulta de score de crédito externo (Serasa/Boa Vista/SCR), atrás de interface. */
public interface CreditScoreProvider {

  /** Nunca {@code null}; {@link CreditScoreLookup#UNKNOWN} quando não há score disponível. */
  CreditScoreLookup lookup(String documentType, String documentDigits);
}
