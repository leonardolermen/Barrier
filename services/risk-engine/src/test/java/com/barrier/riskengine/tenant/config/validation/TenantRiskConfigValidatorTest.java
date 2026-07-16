package com.barrier.riskengine.tenant.config.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TenantRiskConfigValidatorTest {

  private final TenantRiskConfigValidator validator =
      new TenantRiskConfigValidator("6", "150", "200");

  @Test
  void aceitaParametroConfiguravelDentroDoRange() {
    validator.validate("NEW_COMPANY", "months", "12");
    validator.validate("NEW_COMPANY", "score", "300");
    validator.validate("SENSITIVE_CNAE", "cnae-codes", "1234567,7654321");
  }

  @Test
  void rejeitaRuleCodeForaDaAllowlist() {
    assertThatThrownBy(() -> validator.validate("SANCTION", "score", "100"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SANCTION");
  }

  @Test
  void rejeitaParamKeyDesconhecido() {
    assertThatThrownBy(() -> validator.validate("NEW_COMPANY", "unknown-param", "1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejeitaValorForaDoRange() {
    assertThatThrownBy(() -> validator.validate("NEW_COMPANY", "months", "0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validator.validate("NEW_COMPANY", "score", "0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validator.validate("NEW_COMPANY", "months", "999"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejeitaValorNaoNumericoEmParametroNumerico() {
    assertThatThrownBy(() -> validator.validate("NEW_COMPANY", "months", "abc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejeitaListaDeCnaeForaDoFormato() {
    assertThatThrownBy(() -> validator.validate("SENSITIVE_CNAE", "cnae-codes", "123"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validator.validate("SENSITIVE_CNAE", "cnae-codes", "abcdefg"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void defaultsOfDevolveOsDefaultsConhecidos() {
    assertThat(validator.defaultsOf("NEW_COMPANY")).containsEntry("months", "6").containsEntry("score", "150");
    assertThat(validator.defaultsOf("PEP")).isEmpty();
  }

  @Test
  void ruleCodesListaAsConfiguraveis() {
    assertThat(validator.ruleCodes()).containsExactlyInAnyOrder("NEW_COMPANY", "SENSITIVE_CNAE");
  }
}
