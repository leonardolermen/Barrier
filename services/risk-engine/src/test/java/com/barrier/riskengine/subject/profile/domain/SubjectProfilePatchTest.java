package com.barrier.riskengine.subject.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubjectProfilePatchTest {

  @Test
  void camposNaoInformadosPreservamOValorExistente() {
    UUID subjectId = UUID.randomUUID();
    SubjectProfile.Address address =
        new SubjectProfile.Address("Rua A", "10", null, "Centro", "São Paulo", "SP", "01000-000");
    SubjectProfile current =
        new SubjectProfile(
            UUID.randomUUID(),
            subjectId,
            LocalDate.of(1990, 1, 1),
            null,
            "Brasileira",
            "Engenheira",
            null,
            address,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.now(),
            Instant.now());

    SubjectProfilePatch patch =
        new SubjectProfilePatch(
            null, null, null, "Nova ocupação", null, null, null, null, null, null, null, null,
            null, null);

    SubjectProfile updated = patch.applyTo(current);

    assertThat(updated.occupation()).isEqualTo("Nova ocupação");
    assertThat(updated.birthDate()).isEqualTo(current.birthDate());
    assertThat(updated.nationality()).isEqualTo(current.nationality());
    assertThat(updated.address()).isEqualTo(current.address());
  }

  @Test
  void partnersVazioNoPatchNaoApagaOsExistentes() {
    UUID subjectId = UUID.randomUUID();
    List<SubjectProfile.Partner> existingPartners =
        List.of(new SubjectProfile.Partner("Sócio A", false, false, "Sócio"));
    SubjectProfile current =
        new SubjectProfile(
            UUID.randomUUID(),
            subjectId,
            null,
            LocalDate.of(2010, 5, 20),
            null,
            null,
            null,
            null,
            null,
            null,
            "6201-5",
            "Desenvolvimento de programas de computador",
            null,
            "Fulano de Tal",
            "12345678900",
            existingPartners,
            Instant.now(),
            Instant.now());

    SubjectProfilePatch patch =
        new SubjectProfilePatch(
            null, null, null, null, null, null, null, null, null, null, null, null, null,
            List.of());

    SubjectProfile updated = patch.applyTo(current);

    assertThat(updated.partners()).hasSize(1);
  }
}
