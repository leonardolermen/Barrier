package com.barrier.commons.name;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NameTokensTest {

  private static final double THRESHOLD = 0.90;

  @Test
  void descartaConectivosEFormasSocietarias() {
    assertThat(NameTokens.of("José da Silva").values()).containsExactly("JOSE", "SILVA");
    assertThat(NameTokens.of("Acme Comercio LTDA").values()).containsExactly("ACME", "COMERCIO");
  }

  @Test
  void nomeSoDeRuidoFicaVazio() {
    assertThat(NameTokens.of("LTDA").isEmpty()).isTrue();
    assertThat(NameTokens.of(null).isEmpty()).isTrue();
    assertThat(NameTokens.of("   ").isEmpty()).isTrue();
  }

  /**
   * A relação de cobertura é direcional: o nome mais curto pode ser coberto pelo mais longo, nunca o
   * contrário — senão bastaria acrescentar sobrenomes para casar com qualquer titular.
   */
  @Test
  void coberturaEDirecional() {
    NameTokens curto = NameTokens.of("Joao Silva");
    NameTokens longo = NameTokens.of("Joao Pereira da Silva");

    assertThat(curto.coveredBy(longo, THRESHOLD)).isTrue();
    assertThat(longo.coveredBy(curto, THRESHOLD)).isFalse();
  }

  @Test
  void umTokenSoNaoCobreNomeComposto() {
    assertThat(NameTokens.of("Silva").coveredBy(NameTokens.of("Joao Pereira da Silva"), THRESHOLD))
        .isFalse();
  }

  /** Screening: basta que um dos lados cubra o outro, em qualquer direção. */
  @ParameterizedTest(name = "\"{0}\" ~ \"{1}\"")
  @CsvSource({
    "Jose Antonio da Silva,'SILVA, JOSE ANTONIO'",
    "Antonio Santos de Oliveira,ANTONIO SANTOS",
    "Acme Comercio,ACME COMERCIO DE ALIMENTOS LTDA",
  })
  void matchesEitherWayCasaNosDoisSentidos(String a, String b) {
    assertThat(NameSimilarity.matchesEitherWay(a, b, THRESHOLD)).isTrue();
    assertThat(NameSimilarity.matchesEitherWay(b, a, THRESHOLD)).isTrue();
  }

  @ParameterizedTest(name = "\"{0}\" NÃO ~ \"{1}\"")
  @CsvSource({
    "Carlos Eduardo Nunes,CARLOS ROBERTO MENDES",
    "Maria Souza,JOAO PEREIRA DA SILVA",
  })
  void matchesEitherWayNaoCasaEntidadesDiferentes(String a, String b) {
    assertThat(NameSimilarity.matchesEitherWay(a, b, THRESHOLD)).isFalse();
    assertThat(NameSimilarity.matchesEitherWay(b, a, THRESHOLD)).isFalse();
  }

  @Test
  void similaridadeSimetricaPegaAMelhorDasDuasDirecoes() {
    assertThat(NameSimilarity.similarityEitherWay("Jose Antonio Silva", "SILVA, JOSE ANTONIO"))
        .isEqualTo(1.0);
    assertThat(NameSimilarity.similarityEitherWay("Maria Souza", "JOAO PEREIRA")).isLessThan(0.7);
  }
}
