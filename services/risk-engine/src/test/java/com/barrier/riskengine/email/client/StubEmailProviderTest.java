package com.barrier.riskengine.email.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class StubEmailProviderTest {

  @Test
  void dominioDescartavelConhecidoCasa() {
    var provider = new StubEmailProvider(Set.of("mailinator.com"));

    assertThat(provider.lookup("teste@mailinator.com").disposableDomain()).isTrue();
  }

  @Test
  void dominioComumNaoCasa() {
    var provider = new StubEmailProvider(Set.of("mailinator.com"));

    assertThat(provider.lookup("fulano@gmail.com").disposableDomain()).isFalse();
  }

  @Test
  void casamentoEhCaseInsensitive() {
    var provider = new StubEmailProvider(Set.of("mailinator.com"));

    assertThat(provider.lookup("teste@MAILINATOR.COM").disposableDomain()).isTrue();
  }

  @Test
  void emailSemArrobaDevolveDesconhecido() {
    var provider = new StubEmailProvider(Set.of("mailinator.com"));

    assertThat(provider.lookup("invalido")).isEqualTo(EmailLookup.UNKNOWN);
  }

  @Test
  void emailNuloDevolveDesconhecido() {
    var provider = new StubEmailProvider(Set.of("mailinator.com"));

    assertThat(provider.lookup(null)).isEqualTo(EmailLookup.UNKNOWN);
  }
}
