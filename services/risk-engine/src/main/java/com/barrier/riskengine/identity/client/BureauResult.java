package com.barrier.riskengine.identity.client;

/** Desfecho retornado por um bureau (indisponibilidade é sinalizada por exceção). */
public record BureauResult(Outcome outcome, String detail) {

  public enum Outcome {
    MATCH,
    NOT_FOUND,
    MISMATCH
  }

  public static BureauResult match(String detail) {
    return new BureauResult(Outcome.MATCH, detail);
  }
}
