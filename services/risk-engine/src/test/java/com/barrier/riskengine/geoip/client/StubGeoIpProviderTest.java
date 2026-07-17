package com.barrier.riskengine.geoip.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StubGeoIpProviderTest {

  @Test
  void semMapeamentoDevolveDesconhecido() {
    var provider = new StubGeoIpProvider("");

    assertThat(provider.lookup("200.1.2.3")).isEqualTo(GeoIpLookup.UNKNOWN);
  }

  @Test
  void ipComPrefixoMapeadoCasa() {
    var provider = new StubGeoIpProvider("200.1=SP,201.2=RJ");

    assertThat(provider.lookup("200.1.2.3").state()).isEqualTo("SP");
    assertThat(provider.lookup("201.2.9.9").state()).isEqualTo("RJ");
  }

  @Test
  void ipSemPrefixoMapeadoDevolveDesconhecido() {
    var provider = new StubGeoIpProvider("200.1=SP");

    assertThat(provider.lookup("9.9.9.9")).isEqualTo(GeoIpLookup.UNKNOWN);
  }

  @Test
  void ipNuloDevolveDesconhecido() {
    var provider = new StubGeoIpProvider("200.1=SP");

    assertThat(provider.lookup(null)).isEqualTo(GeoIpLookup.UNKNOWN);
  }
}
