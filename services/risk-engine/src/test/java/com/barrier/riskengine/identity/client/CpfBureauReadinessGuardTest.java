package com.barrier.riskengine.identity.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CpfBureauReadinessGuardTest {

  private static final BureauProvider SIMULADO = new FakeCpfBureauProvider();
  private static final BureauProvider CNPJ_REAL = fake("brasilapi", "CNPJ");
  private static final BureauProvider CPF_REAL = fake("bureau-cpf", "CPF");

  @Test
  void falhaAoSubirEmProducaoQuandoOSimuladoEOUnicoProviderDeCpf() {
    CpfBureauReadinessGuard guard = new CpfBureauReadinessGuard(List.of(CNPJ_REAL, SIMULADO), prod());

    assertThatThrownBy(() -> guard.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nenhum provider autoritativo")
        .hasMessageContaining("simulado");
  }

  /**
   * Regressão da armadilha que o bureau simulado cria: apontar a {@code base-url} de um bureau
   * "real" para a própria máquina o torna autoritativo e desarma a checagem acima — sem ninguém
   * ter decidido isso. É config de dev copiada para produção.
   */
  @Test
  void falhaAoSubirEmProducaoComBureauApontandoParaLocalhost() {
    MockEnvironment prod = prod();
    prod.setProperty("barrier.identity.bigboost.base-url", "http://localhost:8080");

    CpfBureauReadinessGuard guard = new CpfBureauReadinessGuard(List.of(CPF_REAL), prod);

    assertThatThrownBy(() -> guard.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("endereço local");
  }

  @Test
  void naoFalhaComBureauRealApontandoParaEndpointPublico() {
    MockEnvironment prod = prod();
    prod.setProperty(
        "barrier.identity.bigboost.base-url", "https://plataforma.bigdatacorp.com.br");

    assertThatCode(() -> new CpfBureauReadinessGuard(List.of(CPF_REAL), prod).run(null))
        .doesNotThrowAnyException();
  }

  @Test
  void falhaAoSubirEmProducaoSemNenhumProviderDeCpf() {
    CpfBureauReadinessGuard guard = new CpfBureauReadinessGuard(List.of(CNPJ_REAL), prod());

    assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void naoFalhaEmProducaoComBureauRealDeCpf() {
    CpfBureauReadinessGuard guard =
        new CpfBureauReadinessGuard(List.of(CNPJ_REAL, CPF_REAL, SIMULADO), prod());

    assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
  }

  @Test
  void naoFalhaForaDeProducaoMesmoSoComOSimulado() {
    MockEnvironment dev = new MockEnvironment();
    dev.setActiveProfiles("dev");

    assertThatCode(() -> new CpfBureauReadinessGuard(List.of(SIMULADO), dev).run(null))
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
