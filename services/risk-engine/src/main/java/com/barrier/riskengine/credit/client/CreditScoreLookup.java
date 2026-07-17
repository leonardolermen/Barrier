package com.barrier.riskengine.credit.client;

/** Score de crédito externo (escala 0–1000, mais alto = melhor); {@code score == null} = sem dado. */
public record CreditScoreLookup(Integer score) {

  public static final CreditScoreLookup UNKNOWN = new CreditScoreLookup(null);
}
