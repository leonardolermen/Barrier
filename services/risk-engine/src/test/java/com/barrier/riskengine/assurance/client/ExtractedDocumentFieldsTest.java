package com.barrier.riskengine.assurance.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * O {@code toString} automático do record vazaria nome e CPF/CNPJ do titular no primeiro log de
 * depuração que capturasse este objeto — PII latente que a revisão final apontou.
 */
class ExtractedDocumentFieldsTest {

  @Test
  void toStringNaoExpoeNomeNemDocumento() {
    ExtractedDocumentFields fields =
        new ExtractedDocumentFields("Fulano de Tal", "12345678900", LocalDate.of(1990, 1, 1));

    String text = fields.toString();

    assertThat(text).doesNotContain("Fulano de Tal", "12345678900", "1990");
  }
}
