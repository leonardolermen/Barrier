package com.barrier.riskengine.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ApiKeyMaterialTest {

  @Test
  void chaveEmitidaAutenticaContraOProprioHash() {
    ApiKeyMaterial.Generated generated = ApiKeyMaterial.generate();

    ApiKeyMaterial.Presented parsed = ApiKeyMaterial.parse(generated.presentedValue()).orElseThrow();

    assertThat(parsed.keyId()).isEqualTo(generated.keyId());
    assertThat(ApiKeyMaterial.matches(parsed.secret(), generated.secretHash())).isTrue();
  }

  /**
   * Regressão de um bug que só aparecia às vezes: o alfabeto base64url inclui {@code _}, e o
   * {@code split("_")} sem limite partia o segredo, recusando ~metade das chaves emitidas. Cem
   * rodadas tornam a detecção determinística.
   */
  @Test
  void toleraUnderscoreNoSegredoGerado() {
    IntStream.range(0, 100)
        .forEach(
            i -> {
              ApiKeyMaterial.Generated generated = ApiKeyMaterial.generate();
              ApiKeyMaterial.Presented parsed =
                  ApiKeyMaterial.parse(generated.presentedValue()).orElseThrow();
              assertThat(ApiKeyMaterial.matches(parsed.secret(), generated.secretHash())).isTrue();
            });
  }

  @Test
  void chavesEmitidasSaoDistintas() {
    assertThat(ApiKeyMaterial.generate().keyId()).isNotEqualTo(ApiKeyMaterial.generate().keyId());
  }

  @Test
  void segredoErradoNaoCasa() {
    ApiKeyMaterial.Generated generated = ApiKeyMaterial.generate();

    assertThat(ApiKeyMaterial.matches("outro-segredo", generated.secretHash())).isFalse();
  }

  @Test
  void formatoInvalidoNaoParseia() {
    assertThat(ApiKeyMaterial.parse(null)).isEmpty();
    assertThat(ApiKeyMaterial.parse("")).isEmpty();
    assertThat(ApiKeyMaterial.parse("sem-prefixo_abc_def")).isEmpty();
    assertThat(ApiKeyMaterial.parse("brr_soidsemsegredo")).isEmpty();
    assertThat(ApiKeyMaterial.parse("brr__segredo")).isEmpty();
  }

  @Test
  void oValorApresentadoTrazOPrefixoDeVarreduraDeVazamento() {
    assertThat(ApiKeyMaterial.generate().presentedValue()).startsWith("brr_");
  }
}
