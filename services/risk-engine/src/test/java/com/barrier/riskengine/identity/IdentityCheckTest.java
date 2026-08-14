package com.barrier.riskengine.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import org.junit.jupiter.api.Test;

/** Reuso de verificação de identidade: procedência sem duplicar PII. Ver V040. */
class IdentityCheckTest {

  @Test
  void checkReaproveitadoCopiaDesfechoEApontaParaOriginal() {
    IdentityCheck original =
        IdentityCheck.create(
            "aval-1", "tenant-a", "CPF", "11144477735", "MARIA SILVA",
            IdentityStatus.VERIFIED, "bigboost", "titular regular", "query-99", "{}");

    IdentityCheck reuso = IdentityCheck.reusing("aval-2", original);

    assertThat(reuso.assessmentId()).isEqualTo("aval-2");
    assertThat(reuso.status()).isEqualTo(IdentityStatus.VERIFIED);
    assertThat(reuso.provider()).isEqualTo("bigboost");
    assertThat(reuso.providerReference()).isEqualTo("query-99");
    assertThat(reuso.reusedFromId()).isEqualTo(original.id());
    assertThat(reuso.id()).isNotEqualTo(original.id());
  }

  @Test
  void checkReaproveitadoNaoCopiaARespostaBruta() {
    IdentityCheck original =
        IdentityCheck.create(
            "aval-1", "tenant-a", "CPF", "11144477735", "MARIA SILVA",
            IdentityStatus.VERIFIED, "bigboost", "titular regular", "query-99", "{\"a\":1}");

    assertThat(IdentityCheck.reusing("aval-2", original).rawResponse()).isNull();
  }
}
