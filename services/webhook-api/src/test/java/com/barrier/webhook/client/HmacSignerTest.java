package com.barrier.webhook.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HmacSignerTest {

  private final HmacSigner signer = new HmacSigner();

  @Test
  void assinaturaEDeterministicaEPrefixada() {
    String a = signer.sign("{\"x\":1}", "secret");
    String b = signer.sign("{\"x\":1}", "secret");

    assertThat(a).isEqualTo(b).startsWith("sha256=");
  }

  @Test
  void segredosDiferentesGeramAssinaturasDiferentes() {
    assertThat(signer.sign("body", "s1")).isNotEqualTo(signer.sign("body", "s2"));
  }
}
