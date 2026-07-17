package com.barrier.riskengine.phone.client;

/** Resultado de uma consulta de telefone. */
public record PhoneLookup(boolean voip) {

  public static final PhoneLookup UNKNOWN = new PhoneLookup(false);
}
