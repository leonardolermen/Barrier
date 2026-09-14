package com.barrier.riskengine.riskpolicy.service;

import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.time.LocalDate;

/** Fixture mínima para os testes deste pacote: só o {@code birthDate} pedido, resto nulo. */
final class SubjectProfileFixtures {

  private SubjectProfileFixtures() {}

  static SubjectProfile comNascimento(LocalDate birthDate) {
    return new SubjectProfile(
        null, null, null, birthDate, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);
  }
}
