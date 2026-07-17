package com.barrier.riskengine.geoip.client;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider de GeoIP para dev/testes: casa o IP por prefixo contra um mapa configurável
 * ({@code barrier.geoip.mappings}, CSV no formato {@code prefixo=UF,prefixo2=UF2}, vazio por
 * padrão — sem sinal em dev). Substituir por MaxMind/IPinfo/etc. em produção, atrás da mesma
 * interface.
 */
@Component
public class StubGeoIpProvider implements GeoIpProvider {

  private final Map<String, String> prefixToState;

  public StubGeoIpProvider(@Value("${barrier.geoip.mappings:}") String mappings) {
    this.prefixToState = parse(mappings);
  }

  @Override
  public GeoIpLookup lookup(String ip) {
    if (ip == null) {
      return GeoIpLookup.UNKNOWN;
    }
    return prefixToState.entrySet().stream()
        .filter(e -> ip.startsWith(e.getKey()))
        .map(e -> new GeoIpLookup("BR", e.getValue()))
        .findFirst()
        .orElse(GeoIpLookup.UNKNOWN);
  }

  private static Map<String, String> parse(String csv) {
    Map<String, String> result = new LinkedHashMap<>();
    if (csv == null || csv.isBlank()) {
      return result;
    }
    for (String entry : csv.split(",")) {
      String[] parts = entry.split("=", 2);
      if (parts.length == 2) {
        result.put(parts[0].trim(), parts[1].trim());
      }
    }
    return result;
  }
}
