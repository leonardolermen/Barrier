package com.barrier.riskengine.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AdminApiKeyReadinessGuardTest {

  private static final String CHAVE_FORTE = "chave-de-admin-com-tamanho-suficiente-123";

  @Test
  void falhaAoSubirEmProducaoSemChave() {
    assertThatThrownBy(() -> new AdminApiKeyReadinessGuard("", prod()).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("barrier.admin.api-key");
  }

  @Test
  void falhaAoSubirEmProducaoComChaveCurta() {
    assertThatThrownBy(() -> new AdminApiKeyReadinessGuard("curta", prod()).run(null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void naoFalhaEmProducaoComChaveForte() {
    assertThatCode(() -> new AdminApiKeyReadinessGuard(CHAVE_FORTE, prod()).run(null))
        .doesNotThrowAnyException();
  }

  @Test
  void naoFalhaForaDeProducaoMesmoSemChave() {
    MockEnvironment dev = new MockEnvironment();
    dev.setActiveProfiles("dev");

    assertThatCode(() -> new AdminApiKeyReadinessGuard("", dev).run(null))
        .doesNotThrowAnyException();
  }

  private static MockEnvironment prod() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    return prod;
  }
}
