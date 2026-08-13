package com.barrier.riskengine.subject.repository.interfaces;

import com.barrier.riskengine.subject.domain.Subject;
import java.util.Optional;
import java.util.UUID;

/** Repositório de domínio dos subjects e da associação com tenants. */
public interface SubjectRepository {

  Subject save(Subject subject);

  Optional<Subject> findByDocument(String documentType, String document);

  /** Cria o vínculo tenant↔subject se não existir; atualiza {@code last_seen} se já existir. */
  void link(String tenantId, UUID subjectId);

  boolean isLinked(String tenantId, UUID subjectId);
}
