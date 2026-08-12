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

  /**
   * O furo que esta mudança fecha: todos os campos preenchidos com dados plausíveis e inventados
   * satisfaziam o gate e liberavam aprovação automática. Preenchido não é verificado.
   */
  @Test
  void cpfPreenchidoMasNaoVerificadoNaoEstaCompleto() {
    RegistrationCompleteness completeness =
        RegistrationCompleteness.evaluate("CPF", cpfPreenchido(), java.util.Set.of());

    assertThat(completeness.complete()).isFalse();
    assertThat(completeness.missingFields())
        .containsExactlyInAnyOrder(
            "data de nascimento não conferida com o bureau", "telefone ou e-mail verificado");
  }

  /** Um canal basta: cobrar telefone E e-mail travaria cliente legítimo que só tem um. */
  @Test
  void umCanalVerificadoBastaJuntoComONascimentoConferido() {
    RegistrationCompleteness completeness =
        RegistrationCompleteness.evaluate(
            "CPF",
            cpfPreenchido(),
            java.util.Set.of(VerifiableField.BIRTH_DATE, VerifiableField.EMAIL));

    assertThat(completeness.complete()).isTrue();
  }

  private SubjectProfile cpfPreenchido() {
    return new SubjectProfile(
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

    RegistrationCompleteness completeness =
        RegistrationCompleteness.evaluate(
            "CPF",
            profile,
            java.util.Set.of(VerifiableField.BIRTH_DATE, VerifiableField.PHONE));

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
