package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneAreaCodeTest {

  @Test
  void extraiUfDoDddSemFormatacao() {
    assertThat(PhoneAreaCode.ufOf("11912345678")).isEqualTo("SP");
  }

  @Test
  void extraiUfDoDddComFormatacao() {
    assertThat(PhoneAreaCode.ufOf("(21) 91234-5678")).isEqualTo("RJ");
  }

  @Test
  void extraiUfIgnorandoCodigoDoPais() {
    assertThat(PhoneAreaCode.ufOf("+55 47 91234-5678")).isEqualTo("SC");
  }

  @Test
  void dddDesconhecidoDevolveNull() {
    assertThat(PhoneAreaCode.ufOf("00 91234-5678")).isNull();
  }

  @Test
  void telefoneMuitoCurtoDevolveNull() {
    assertThat(PhoneAreaCode.ufOf("1234")).isNull();
  }

  @Test
  void telefoneNuloDevolveNull() {
    assertThat(PhoneAreaCode.ufOf(null)).isNull();
  }
}
