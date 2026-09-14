package com.barrier.riskengine.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Confirma, em vez de assumir, a nota de correção do spec (§9, Task 8): {@code ApiRoutes} é uma
 * <b>denylist</b>, então {@code /v1/policies} e {@code /v1/policy-fields} já nascem tenant-scoped
 * sem precisar tocar o padrão {@code ADMIN}. Vive neste pacote porque {@code ApiRoutes} é
 * package-private.
 */
class ApiRoutesPolicyClassificationTest {

  @Test
  void rotasDePoliticaSaoDeParceiroENaoAdministrativas() {
    assertThat(ApiRoutes.isTenantScoped("/v1/policies")).isTrue();
    assertThat(ApiRoutes.isAdmin("/v1/policies")).isFalse();

    assertThat(ApiRoutes.isTenantScoped("/v1/policy-fields")).isTrue();
    assertThat(ApiRoutes.isAdmin("/v1/policy-fields")).isFalse();
  }

  @Test
  void subcaminhosDePoliticaTambemSaoDeParceiro() {
    assertThat(ApiRoutes.isTenantScoped("/v1/policies/3")).isTrue();
    assertThat(ApiRoutes.isAdmin("/v1/policies/3")).isFalse();

    assertThat(ApiRoutes.isTenantScoped("/v1/policies/3/activate")).isTrue();
    assertThat(ApiRoutes.isAdmin("/v1/policies/3/activate")).isFalse();

    assertThat(ApiRoutes.isTenantScoped("/v1/policies/3/archive")).isTrue();
    assertThat(ApiRoutes.isAdmin("/v1/policies/3/archive")).isFalse();
  }
}
