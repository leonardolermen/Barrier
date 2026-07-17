package com.barrier.riskengine.subject.profile.repository;

import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.util.Optional;
import java.util.UUID;

/** Repositório de domínio do cadastro (CMN 4.753) de um subject. */
public interface SubjectProfileRepository {

  SubjectProfile save(SubjectProfile profile);

  Optional<SubjectProfile> findBySubjectId(UUID subjectId);

  /** Quantos outros subjects (excluindo {@code subjectId}) já usaram este email. */
  long countOtherSubjectsWithEmail(UUID subjectId, String email);
}
