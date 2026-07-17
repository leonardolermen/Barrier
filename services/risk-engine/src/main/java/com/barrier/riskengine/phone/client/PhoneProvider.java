package com.barrier.riskengine.phone.client;

/** Consulta de metadados de um telefone (VoIP, operadora, etc.), atrás de interface. */
public interface PhoneProvider {

  /** Nunca {@code null}; {@link PhoneLookup#UNKNOWN} quando não há dado para o número. */
  PhoneLookup lookup(String phone);
}
