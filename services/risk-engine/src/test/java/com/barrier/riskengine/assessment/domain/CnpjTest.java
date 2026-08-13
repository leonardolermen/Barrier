package com.barrier.riskengine.assessment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.assessment.domain.documents.Cnpj;
import com.barrier.riskengine.assessment.domain.exceptions.InvalidDocumentException;
import org.junit.jupiter.api.Test;

class CnpjTest {

  @Test
  void aceitaCnpjValidoComOuSemMascara() {
    assertThat(new Cnpj("11.222.333/0001-81").digits()).isEqualTo("11222333000181");
    assertThat(new Cnpj("11222333000181").digits()).isEqualTo("11222333000181");
  }

  @Test
  void rejeitaDigitosVerificadoresErrados() {
    assertThatThrownBy(() -> new Cnpj("11222333000100"))
        .isInstanceOf(InvalidDocumentException.class);
  }

  @Test
  void rejeitaTamanhoInvalido() {
    assertThatThrownBy(() -> new Cnpj("123")).isInstanceOf(InvalidDocumentException.class);
  }

  @Test
  void mascaraNaoExpoeRaiz() {
    assertThat(new Cnpj("11.222.333/0001-81").masked()).isEqualTo("**.***.***/0001-**");
  }
}
