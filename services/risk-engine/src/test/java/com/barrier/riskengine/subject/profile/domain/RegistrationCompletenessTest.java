package com.barrier.riskengine.subject.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegistrationCompletenessTest {

  private final UUID subjectId = UUID.randomUUID();
  private static final String TENANT = "acme";

  @Test
  void cpfIncompletoQuandoPerfilEstaEmBranco() {
    RegistrationCompleteness completeness =
        RegistrationCompleteness.evaluate("CPF", SubjectProfile.blank(subjectId, TENANT));

    assertThat(completeness.complete()).isFalse();
    assertThat(completeness.missingFields())
        .contains("data de nascimento", "nacionalidade", "ocupação", "endereço");
  }

  @Test
  void cpfCompletoComTodosOsCamposMinimos() {
    SubjectProfile profile =
        new SubjectProfile(
            UUID.randomUUID(),
            subjectId,
            TENANT,
            LocalDate.of(1990, 1, 1),
            null,
            "Brasileira",
            "Engenheira",
            null,
            new SubjectProfile.Address("Rua A", "10", null, "Centro", "São Paulo", "SP", "01000-000"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    RegistrationCompleteness completeness = RegistrationCompleteness.evaluate("CPF", profile);

    assertThat(completeness.complete()).isTrue();
    assertThat(completeness.missingFields()).isEmpty();
  }

  @Test
  void cnpjIncompletoQuandoPerfilEstaEmBranco() {
    RegistrationCompleteness completeness =
        RegistrationCompleteness.evaluate("CNPJ", SubjectProfile.blank(subjectId, TENANT));

    assertThat(completeness.complete()).isFalse();
    assertThat(completeness.missingFields())
        .contains("data de fundação", "CNAE", "endereço", "representante legal");
  }

  @Test
  void cnpjCompletoComTodosOsCamposMinimos() {
    SubjectProfile profile =
        new SubjectProfile(
            UUID.randomUUID(),
            subjectId,
            TENANT,
            null,
            LocalDate.of(2010, 5, 20),
            null,
            null,
            null,
            new SubjectProfile.Address("Av. B", "500", null, "Centro", "Rio de Janeiro", "RJ", "20000-000"),
            null,
            null,
            "6201-5",
            "Desenvolvimento de programas de computador",
            null,
            "Fulano de Tal",
            "12345678900",
            null,
            null,
            null);

    RegistrationCompleteness completeness = RegistrationCompleteness.evaluate("CNPJ", profile);

    assertThat(completeness.complete()).isTrue();
    assertThat(completeness.missingFields()).isEmpty();
  }

  @Test
  void rejeitaTipoDeDocumentoDesconhecido() {
    assertThatThrownBy(
            () -> RegistrationCompleteness.evaluate("RG", SubjectProfile.blank(subjectId, TENANT)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
