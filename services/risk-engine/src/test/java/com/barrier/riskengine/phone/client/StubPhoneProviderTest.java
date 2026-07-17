package com.barrier.riskengine.phone.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class StubPhoneProviderTest {

  @Test
  void semListaNuncaCasa() {
    var provider = new StubPhoneProvider(Set.of());

    assertThat(provider.lookup("11912345678").voip()).isFalse();
  }

  @Test
  void numeroNaListaEhVoip() {
    var provider = new StubPhoneProvider(Set.of("11912345678"));

    assertThat(provider.lookup("(11) 91234-5678").voip()).isTrue();
  }

  @Test
  void numeroForaDaListaNaoEhVoip() {
    var provider = new StubPhoneProvider(Set.of("11912345678"));

    assertThat(provider.lookup("21988887777").voip()).isFalse();
  }

  @Test
  void telefoneNuloDevolveDesconhecido() {
    var provider = new StubPhoneProvider(Set.of("11912345678"));

    assertThat(provider.lookup(null)).isEqualTo(PhoneLookup.UNKNOWN);
  }
}
