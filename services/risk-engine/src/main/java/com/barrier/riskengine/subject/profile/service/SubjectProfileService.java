package com.barrier.riskengine.subject.profile.service;

import com.barrier.riskengine.subject.profile.domain.RegistrationCompleteness;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import com.barrier.riskengine.subject.profile.domain.SubjectProfilePatch;
import com.barrier.riskengine.subject.profile.repository.SubjectProfileRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso do cadastro (CMN 4.753): atualização progressiva (cliente ou enriquecimento pelo
 * bureau) e checagem de completude, usada como gate antes da aprovação automática de uma
 * avaliação.
 */
@Service
public class SubjectProfileService {

  private final SubjectProfileRepository repository;

  public SubjectProfileService(SubjectProfileRepository repository) {
    this.repository = repository;
  }

  /** Aplica o patch sobre o cadastro existente do subject, criando-o se ainda não existir. */
  @Transactional
  public SubjectProfile upsert(UUID subjectId, SubjectProfilePatch patch) {
    SubjectProfile current =
        repository.findBySubjectId(subjectId).orElseGet(() -> SubjectProfile.blank(subjectId));
    return repository.save(patch.applyTo(current));
  }

  /** Verifica se o cadastro do subject cobre o checklist mínimo do tipo de documento. */
  @Transactional(readOnly = true)
  public RegistrationCompleteness completeness(UUID subjectId, String documentType) {
    return RegistrationCompleteness.evaluate(documentType, find(subjectId));
  }

  /** Cadastro do subject, em branco se ainda não houver nenhum dado preenchido. */
  @Transactional(readOnly = true)
  public SubjectProfile find(UUID subjectId) {
    return repository.findBySubjectId(subjectId).orElseGet(() -> SubjectProfile.blank(subjectId));
  }
}
