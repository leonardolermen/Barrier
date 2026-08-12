package com.barrier.riskengine.assessment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.assessment.domain.documents.Cpf;
import com.barrier.riskengine.assessment.domain.exceptions.InvalidDocumentException;
import org.junit.jupiter.api.Test;

class CpfTest {

  @Test
  void aceitaCpfValidoComOuSemMascara() {
    assertThat(new Cpf("111.444.777-35").digits()).isEqualTo("11144477735");
    assertThat(new Cpf("11144477735").digits()).isEqualTo("11144477735");
  }

  @Test
  void rejeitaDigitosVerificadoresErrados() {
    assertThatThrownBy(() -> new Cpf("11144477700")).isInstanceOf(InvalidDocumentException.class);
  }

  @Test
  void rejeitaTodosDigitosIguais() {
    assertThatThrownBy(() -> new Cpf("11111111111")).isInstanceOf(InvalidDocumentException.class);
  }

  @Test
  void mascaraNaoExpoeDigitosIniciais() {
    assertThat(new Cpf("111.444.777-35").masked()).isEqualTo("***.***.**7-35");
  }
}
