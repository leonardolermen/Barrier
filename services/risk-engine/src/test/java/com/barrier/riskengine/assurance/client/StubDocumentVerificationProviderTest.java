package com.barrier.riskengine.assurance.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubDocumentVerificationProviderTest {

  private static final UUID SUBJECT = UUID.randomUUID();

  private final StubDocumentVerificationProvider provider =
      new StubDocumentVerificationProvider(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

  /**
   * Documento aprovado é o único caso em que os dados extraídos servem de insumo confiável para a
   * task seguinte (verificar o cadastro contra o que a documentoscopia leu).
   */
  @Test
  void documento_aprovado_devolve_campos_extraidos() {
    DocumentVerificationResult result = provider.verify(SUBJECT, "tenant-1", submissao("hash-ok"));

    assertThat(result.check().outcome()).isEqualTo(AssuranceOutcome.PASS);
    assertThat(result.extracted()).isNotNull();
    assertThat(result.extracted().birthDate()).isNotNull();
  }

  /**
   * Documento reprovado não deve produzir dado extraído: não há confiança nenhuma de que os
   * campos lidos são os do titular.
   */
  @Test
  void documento_reprovado_nao_devolve_campos() {
    DocumentVerificationResult result =
        provider.verify(SUBJECT, "tenant-1", submissao("fail-hash"));

    assertThat(result.check().outcome()).isEqualTo(AssuranceOutcome.FAIL);
    assertThat(result.extracted()).isNull();
  }

  private DocumentSubmission submissao(String captureReference) {
    return new DocumentSubmission(captureReference, "RG", "hash-ok");
  }
}
