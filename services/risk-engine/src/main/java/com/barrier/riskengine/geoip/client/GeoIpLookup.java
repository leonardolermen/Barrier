package com.barrier.riskengine.geoip.client;

/** Resultado de uma consulta de geolocalização por IP. Campos {@code null} = desconhecido. */
public record GeoIpLookup(String country, String state) {

  public static final GeoIpLookup UNKNOWN = new GeoIpLookup(null, null);
}
