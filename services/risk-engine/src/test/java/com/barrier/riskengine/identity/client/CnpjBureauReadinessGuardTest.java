package com.barrier.riskengine.identity.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CnpjBureauReadinessGuardTest {

  private static final BureauProvider BRASILAPI = fake("brasilapi", "CNPJ", true);
  private static final BureauProvider BIGBOOST_CNPJ = fake("bigboost-cnpj", "CNPJ", true);
  private static final BureauProvider SIMULADO_CNPJ = fake("simulado-pj", "CNPJ", false);
  private static final BureauProvider CPF_REAL = fake("bigboost", "CPF", true);

  /** O fail-open que este guard fecha: PJ decidida por provider simulado, sem nada falhar. */
  @Test
  void falhaAoSubirEmProducaoQuandoSoHaProviderSimuladoDeCnpj() {
    CnpjBureauReadinessGuard guard =
        new CnpjBureauReadinessGuard(List.of(CPF_REAL, SIMULADO_CNPJ), prod());

    assertThatThrownBy(() -> guard.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nenhum provider autoritativo")
        .hasMessageContaining("simulado-pj");
  }

  @Test
  void falhaAoSubirEmProducaoSemNenhumProviderDeCnpj() {
    assertThatThrownBy(() -> new CnpjBureauReadinessGuard(List.of(CPF_REAL), prod()).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nenhum provider de CNPJ");
  }

  /** Bureau contratado apontado para a própria máquina é simulador com crachá. */
  @Test
  void falhaAoSubirEmProducaoComBigBoostApontandoParaLocalhost() {
    MockEnvironment prod = prod();
    prod.setProperty("barrier.identity.bigboost.base-url", "http://localhost:8080");

    assertThatThrownBy(
            () -> new CnpjBureauReadinessGuard(List.of(BIGBOOST_CNPJ), prod).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("endereço local");
  }

  @Test
  void naoFalhaEmProducaoComBureauContratadoDeCnpj() {
    MockEnvironment prod = prod();
    prod.setProperty("barrier.identity.bigboost.base-url", "https://plataforma.bigdatacorp.com.br");

    assertThatCode(() -> new CnpjBureauReadinessGuard(List.of(BIGBOOST_CNPJ), prod).run(null))
        .doesNotThrowAnyException();
  }

  /**
   * A BrasilAPI sozinha <b>não</b> impede a subida — é bureau real. O guard avisa, porque manter um
   * controle regulatório sobre uma API pública sem SLA é decisão, não default.
   */
  @Test
  void brasilApiSozinhaEmProducaoSobeComAviso() {
    assertThatCode(() -> new CnpjBureauReadinessGuard(List.of(BRASILAPI), prod()).run(null))
        .doesNotThrowAnyException();
  }

  @Test
  void naoFalhaForaDeProducaoMesmoSoComOSimulado() {
    MockEnvironment dev = new MockEnvironment();
    dev.setActiveProfiles("dev");

    assertThatCode(() -> new CnpjBureauReadinessGuard(List.of(SIMULADO_CNPJ), dev).run(null))
        .doesNotThrowAnyException();
  }

  private static MockEnvironment prod() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    return prod;
  }

  private static BureauProvider fake(String name, String supported, boolean authoritative) {
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

      @Override
      public boolean authoritative() {
        return authoritative;
      }
    };
  }
}
