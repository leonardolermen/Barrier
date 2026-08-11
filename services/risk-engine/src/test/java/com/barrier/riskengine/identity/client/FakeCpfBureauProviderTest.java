package com.barrier.riskengine.identity.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FakeCpfBureauProviderTest {

  private final FakeCpfBureauProvider provider = new FakeCpfBureauProvider();

  private BureauResult.Outcome outcomeOf(String cpf) {
    return provider.check(new BureauQuery("CPF", cpf, "Fulano de Tal")).outcome();
  }

  /**
   * A propriedade que mais importa: CPF comum é REGULAR. A primeira versão derivava o cenário de um
   * hash, e o {@code 111.444.777-35} usado em metade dos testes caía em "titular falecido".
   */
  @Test
  void cpfSemPrefixoDeCenarioEhSempreRegular() {
    assertThat(outcomeOf("11144477735")).isEqualTo(BureauResult.Outcome.MATCH);
    assertThat(outcomeOf("52998224725")).isEqualTo(BureauResult.Outcome.MATCH);
    assertThat(outcomeOf("12345678909")).isEqualTo(BureauResult.Outcome.MATCH);
  }

  /** Cada cenário é alcançável pelo quarto dígito, e o mapeamento é o documentado. */
  @ParameterizedTest(name = "999{0}… -> {1}")
  @CsvSource({
    "0,REGULAR",
    "1,TITULAR_FALECIDO",
    "2,OBITO_SEM_STATUS",
    "3,SUSPENSA",
    "4,PENDENTE",
    "5,NULA",
    "6,NAO_ENCONTRADO",
    "7,INDISPONIVEL",
  })
  void quartoDigitoSelecionaOCenario(String seletor, FakeCpfBureauProvider.Scenario esperado) {
    assertThat(FakeCpfBureauProvider.Scenario.of("999" + seletor + "0000000")).isEqualTo(esperado);
  }

  /** Seletor fora da faixa não pode virar cenário por acidente. */
  @Test
  void seletorDesconhecidoCaiEmRegular() {
    assertThat(FakeCpfBureauProvider.Scenario.of("99990000000"))
        .isEqualTo(FakeCpfBureauProvider.Scenario.REGULAR);
    assertThat(FakeCpfBureauProvider.Scenario.of("99980000000"))
        .isEqualTo(FakeCpfBureauProvider.Scenario.REGULAR);
  }

  /**
   * Nunca autoritativo: é o que impede que ele sirva de fallback para um bureau real indisponível
   * e que o {@code CpfBureauReadinessGuard} o aceite como verificação de identidade.
   */
  @Test
  void naoEAutoritativo() {
    assertThat(provider.authoritative()).isFalse();
  }

  @Test
  void naoAtendeCnpj() {
    assertThat(provider.supports("CNPJ")).isFalse();
  }

  /** O cenário de indisponibilidade existe para exercitar o caminho UNAVAILABLE -> revisão. */
  @Test
  void cenarioDeIndisponibilidadeLancaExcecao() {
    assertThatThrownBy(() -> outcomeOf("99970000000"))
        .isInstanceOf(BureauUnavailableException.class);
  }

  @Test
  void cenariosDeObitoDevolvemDeceased() {
    assertThat(outcomeOf("99910000000")).isEqualTo(BureauResult.Outcome.DECEASED);
    assertThat(outcomeOf("99920000000")).isEqualTo(BureauResult.Outcome.DECEASED);
  }

  @Test
  void cenariosIrregularesNaoAprovam() {
    assertThat(outcomeOf("99930000000")).isEqualTo(BureauResult.Outcome.MISMATCH);
    assertThat(outcomeOf("99940000000")).isEqualTo(BureauResult.Outcome.MISMATCH);
    assertThat(outcomeOf("99950000000")).isEqualTo(BureauResult.Outcome.NOT_FOUND);
    assertThat(outcomeOf("99960000000")).isEqualTo(BureauResult.Outcome.NOT_FOUND);
  }
}
