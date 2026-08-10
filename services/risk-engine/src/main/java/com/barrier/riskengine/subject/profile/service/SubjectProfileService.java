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
 *
 * <p>Toda operação é escopada por {@code tenantId}. Não existe assinatura que aceite só o
 * {@code subjectId}: era isso que permitia um parceiro ler e completar o cadastro do cliente de
 * outro (ver migration V024). O tipo do método é a defesa — um endpoint novo não tem como
 * esquecer de passar o tenant.
 */
@Service
public class SubjectProfileService {

  private final SubjectProfileRepository repository;

  public SubjectProfileService(SubjectProfileRepository repository) {
    this.repository = repository;
  }

  /** Aplica o patch sobre o cadastro que este tenant declarou, criando-o se ainda não existir. */
  @Transactional
  public SubjectProfile upsert(UUID subjectId, String tenantId, SubjectProfilePatch patch) {
    SubjectProfile current = find(subjectId, tenantId);
    return repository.save(patch.applyTo(current));
  }

  /** Verifica se o cadastro deste tenant cobre o checklist mínimo do tipo de documento. */
  @Transactional(readOnly = true)
  public RegistrationCompleteness completeness(UUID subjectId, String tenantId, String documentType) {
    return RegistrationCompleteness.evaluate(documentType, find(subjectId, tenantId));
  }

  /** Cadastro declarado por este tenant; em branco se ainda não houver nenhum dado preenchido. */
  @Transactional(readOnly = true)
  public SubjectProfile find(UUID subjectId, String tenantId) {
    return repository
        .findBySubjectIdAndTenantId(subjectId, tenantId)
        .orElseGet(() -> SubjectProfile.blank(subjectId, tenantId));
  }
}
