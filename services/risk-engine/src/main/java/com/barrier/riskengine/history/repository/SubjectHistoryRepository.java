package com.barrier.riskengine.history.repository;

import com.barrier.riskengine.history.domain.SubjectHistoryEvent;
import java.util.List;
import java.util.UUID;

/** Repositório de domínio do histórico interno do subject. */
public interface SubjectHistoryRepository {

  SubjectHistoryEvent save(SubjectHistoryEvent event);

  List<SubjectHistoryEvent> findBySubjectId(UUID subjectId);
}
