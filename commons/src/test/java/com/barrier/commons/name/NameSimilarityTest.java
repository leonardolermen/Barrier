package com.barrier.commons.name;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NameSimilarityTest {

  private static final double THRESHOLD = 0.85;

  @ParameterizedTest(name = "\"{0}\" casa com \"{1}\"")
  @CsvSource({
    // igualdade após normalização (acento, caixa, pontuação)
    "José da Silva,JOSE DA SILVA",
    "MARIA CONCEIÇÃO,Maria Conceicao",
    // subconjunto: o informado omite parte do oficial
    "João Silva,JOAO PEREIRA DA SILVA",
    "Acme Comercio,ACME COMERCIO DE ALIMENTOS LTDA",
    "ACME,ACME LTDA",
    // erro de digitação
    "Jhon Doe Silva,JOHN DOE SILVA",
  })
  void casaVariacoesLegitimasDoMesmoNome(String informado, String oficial) {
    assertThat(NameSimilarity.matches(informado, oficial, THRESHOLD)).isTrue();
  }

  @ParameterizedTest(name = "\"{0}\" NÃO casa com \"{1}\"")
  @CsvSource({
    // pessoas diferentes
    "Maria Souza,JOAO PEREIRA DA SILVA",
    "Transportadora Beta,ACME COMERCIO DE ALIMENTOS LTDA",
    // parentes / homônimos parciais não bastam
    "Carlos Eduardo Nunes,CARLOS ROBERTO MENDES",
  })
  void naoCasaNomesDeEntidadesDiferentes(String informado, String oficial) {
    assertThat(NameSimilarity.matches(informado, oficial, THRESHOLD)).isFalse();
  }

  /**
   * Direção importa: informar menos que o oficial é normal (abreviação), informar <b>mais</b> não —
   * senão bastaria acrescentar sobrenomes para casar com qualquer titular.
   */
  @Test
  void subconjuntoNaoValeNaDirecaoInversa() {
    assertThat(NameSimilarity.matches("JOAO PEREIRA DA SILVA", "JOAO SILVA", THRESHOLD)).isFalse();
  }

  /**
   * Regressão do defeito que o Jaro-Winkler sobre a string inteira introduzia: o bônus de prefixo
   * fazia dois nomes que só compartilham o primeiro nome passarem do limiar.
   */
  @Test
  void primeiroNomeIgualNaoBastaParaCasar() {
    assertThat(NameSimilarity.matches("Carlos Eduardo Nunes", "CARLOS ROBERTO MENDES", THRESHOLD))
        .isFalse();
    assertThat(NameSimilarity.matches("Ana Paula Costa", "ANA BEATRIZ RAMOS", THRESHOLD)).isFalse();
  }

  /** Ordem dos tokens não deve importar — cadastros invertem "sobrenome, nome". */
  @Test
  void ordemDosTokensNaoImporta() {
    assertThat(NameSimilarity.matches("Silva Joao", "JOAO PEREIRA DA SILVA", THRESHOLD)).isTrue();
  }

  /** Um nome só de conectivos/forma societária não pode casar com qualquer coisa por vacuidade. */
  @Test
  void nomeApenasComRuidoNaoCasa() {
    assertThat(NameSimilarity.matches("LTDA", "ACME COMERCIO LTDA", THRESHOLD)).isFalse();
    assertThat(NameSimilarity.matches("DA SILVA", "JOAO PEREIRA DA SILVA", THRESHOLD)).isFalse();
  }

  /** Sem um dos lados não há como confirmar identidade — nunca responde "casa". */
  @Test
  void ausenciaDeQualquerUmDosNomesNaoCasa() {
    assertThat(NameSimilarity.matches(null, "ACME LTDA", THRESHOLD)).isFalse();
    assertThat(NameSimilarity.matches("ACME LTDA", null, THRESHOLD)).isFalse();
    assertThat(NameSimilarity.matches("  ", "ACME LTDA", THRESHOLD)).isFalse();
  }

  @Test
  void similaridadeExpoeOTokenPiorCasadoParaAEvidencia() {
    assertThat(NameSimilarity.similarity("JOSE DA SILVA", "JOSE DA SILVA")).isEqualTo(1.0);
    assertThat(NameSimilarity.similarity("Maria Souza", "JOAO PEREIRA")).isLessThan(0.7);
    // "CARLOS" casa perfeitamente, mas o token fraco é que define a medida
    assertThat(NameSimilarity.similarity("Carlos Eduardo", "CARLOS ROBERTO")).isLessThan(THRESHOLD);
  }
}
