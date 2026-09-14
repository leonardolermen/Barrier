package com.barrier.riskengine.riskpolicy.service;

import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Fixtures mínimas para os testes deste pacote: um único campo pedido, resto nulo. */
final class SubjectProfileFixtures {

  private SubjectProfileFixtures() {}

  static SubjectProfile comNascimento(LocalDate birthDate) {
    return new SubjectProfile(
        null, null, null, birthDate, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);
  }

  static SubjectProfile comOcupacao(String occupation) {
    return new SubjectProfile(
        null, null, null, null, null, null, occupation, null, null, null, null, null, null, null,
        null, null, null, null, null);
  }

  static SubjectProfile comCapitalSocial(BigDecimal shareCapital) {
    return new SubjectProfile(
        null, null, null, null, null, null, null, null, null, null, null, null, null,
        shareCapital, null, null, null, null, null);
  }
}
