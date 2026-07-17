package com.barrier.riskengine.geoip.client;

/** Consulta de geolocalização por IP, atrás de interface (Gateway/Adapter). */
public interface GeoIpProvider {

  /** Nunca {@code null}; {@link GeoIpLookup#UNKNOWN} quando não há dado para o IP. */
  GeoIpLookup lookup(String ip);
}
