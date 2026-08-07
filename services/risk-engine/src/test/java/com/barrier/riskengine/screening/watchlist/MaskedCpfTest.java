package com.barrier.riskengine.screening.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskedCpfTest {

  @Test
  void extraiOsSeisDigitosCentraisDeUmCpfCompleto() {
    assertThat(MaskedCpf.centralDigitsOf("52998224725")).isEqualTo("982247");
  }

  @Test
  void cnpjNaoTemDigitosCentraisDeCpf() {
    assertThat(MaskedCpf.centralDigitsOf("11444777000161")).isNull();
    assertThat(MaskedCpf.centralDigitsOf(null)).isNull();
  }

  @Test
  void reconheceOFormatoMascaradoPublicadoPelaCgu() {
    assertThat(MaskedCpf.parsePublished("***.982.247-**")).isEqualTo("982247");
    assertThat(MaskedCpf.parsePublished("982247")).isEqualTo("982247");
  }

  @Test
  void cpfCompletoPublicadoTambemRendeOsCentrais() {
    assertThat(MaskedCpf.parsePublished("529.982.247-25")).isEqualTo("982247");
  }

  /** Formato inesperado vira "sem discriminador", nunca "não casa". */
  @Test
  void formatoIrreconheciveNaoViraDiscriminador() {
    assertThat(MaskedCpf.parsePublished("")).isNull();
    assertThat(MaskedCpf.parsePublished(null)).isNull();
    assertThat(MaskedCpf.parsePublished("***.***.***-**")).isNull();
    assertThat(MaskedCpf.parsePublished("12345")).isNull();
  }

  @Test
  void distingueDocumentoCompletoDeMascara() {
    assertThat(MaskedCpf.isComplete("529.982.247-25")).isTrue();
    assertThat(MaskedCpf.isComplete("11.444.777/0001-61")).isTrue();
    assertThat(MaskedCpf.isComplete("***.982.247-**")).isFalse();
    assertThat(MaskedCpf.isComplete(null)).isFalse();
  }
}
