package com.barrier.riskengine.assurance.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AssuranceConsentTest {

  @Test
  void recusa_consentimento_sem_referencia() {
    assertThatThrownBy(() -> new AssuranceConsent(" ", "KYC", Instant.now()).validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("referência");
    assertThatThrownBy(() -> new AssuranceConsent(null, "KYC", Instant.now()).validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("referência");
  }

  @Test
  void recusa_consentimento_sem_finalidade() {
    assertThatThrownBy(
            () -> new AssuranceConsent("ref-1", "  ", Instant.now()).validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("finalidade");
  }

  @Test
  void recusa_consentimento_no_futuro() {
    assertThatThrownBy(
            () ->
                new AssuranceConsent("ref-1", "KYC", Instant.now().plusSeconds(3600)).validate())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aceita_consentimento_completo() {
    assertThatCode(() -> new AssuranceConsent("ref-1", "KYC", Instant.now()).validate())
        .doesNotThrowAnyException();
  }
}
