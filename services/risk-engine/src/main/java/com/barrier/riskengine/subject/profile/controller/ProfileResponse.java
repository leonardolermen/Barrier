package com.barrier.riskengine.subject.profile.controller;

import com.barrier.riskengine.subject.profile.domain.RegistrationCompleteness;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.util.List;

/** Cadastro do subject mais o checklist de completude (CMN 4.753) para o tipo de documento. */
public record ProfileResponse(
    boolean complete, List<String> missingFields, SubjectProfile profile) {

  public static ProfileResponse of(RegistrationCompleteness completeness, SubjectProfile profile) {
    return new ProfileResponse(completeness.complete(), completeness.missingFields(), profile);
  }
}
