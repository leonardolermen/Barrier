package com.barrier.riskengine.identity.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CpfBureauReadinessGuardTest {

  private static final BureauProvider STUB = new StubBureauProvider();
  private static final BureauProvider CNPJ_REAL = fake("brasilapi", "CNPJ");
  private static final BureauProvider CPF_REAL = fake("bureau-cpf", "CPF");

  @Test
  void falhaAoSubirEmProducaoQuandoOStubEOUnicoProviderDeCpf() {
    CpfBureauReadinessGuard guard =
        new CpfBureauReadinessGuard(List.of(CNPJ_REAL, STUB), prod());

    assertThatThrownBy(() -> guard.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stub");
  }

  @Test
  void falhaAoSubirEmProducaoSemNenhumProviderDeCpf() {
    CpfBureauReadinessGuard guard = new CpfBureauReadinessGuard(List.of(CNPJ_REAL), prod());

    assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void naoFalhaEmProducaoComBureauRealDeCpf() {
    CpfBureauReadinessGuard guard =
        new CpfBureauReadinessGuard(List.of(CNPJ_REAL, CPF_REAL, STUB), prod());

    assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
  }

  @Test
  void naoFalhaForaDeProducaoMesmoSoComOStub() {
    MockEnvironment dev = new MockEnvironment();
    dev.setActiveProfiles("dev");

    assertThatCode(() -> new CpfBureauReadinessGuard(List.of(STUB), dev).run(null))
        .doesNotThrowAnyException();
  }

  private static MockEnvironment prod() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    return prod;
  }

  private static BureauProvider fake(String name, String supported) {
    return new BureauProvider() {
      @Override
      public boolean supports(String documentType) {
        return supported.equals(documentType);
      }

      @Override
      public BureauResult check(BureauQuery query) {
        return BureauResult.match("fake");
      }

      @Override
      public String name() {
        return name;
      }
    };
  }
}
