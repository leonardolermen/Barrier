package com.barrier.riskengine.subject.profile.repository.interfaces;

import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.util.Optional;
import java.util.UUID;

/** Repositório de domínio do cadastro (CMN 4.753) de um subject. */
public interface SubjectProfileRepository {

  SubjectProfile save(SubjectProfile profile);

  /**
   * Cadastro que <b>este tenant</b> declarou para o subject. Não existe busca só por subject: o
   * cadastro de um parceiro não é visível para outro (ver migration V024).
   */
  Optional<SubjectProfile> findBySubjectIdAndTenantId(UUID subjectId, String tenantId);
}
