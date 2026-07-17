package com.barrier.riskengine.history.service;

import com.barrier.riskengine.history.domain.HistoryEventType;
import com.barrier.riskengine.history.domain.SubjectHistoryEvent;
import com.barrier.riskengine.history.repository.SubjectHistoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso do histórico interno: registrar eventos e consultar por subject. */
@Service
public class SubjectHistoryService {

  private final SubjectHistoryRepository repository;

  public SubjectHistoryService(SubjectHistoryRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public SubjectHistoryEvent record(
      UUID subjectId, HistoryEventType eventType, String detail, Instant occurredAt) {
    return repository.save(
        SubjectHistoryEvent.create(
            subjectId, eventType, detail, occurredAt == null ? Instant.now() : occurredAt));
  }

  @Transactional(readOnly = true)
  public List<SubjectHistoryEvent> findBySubjectId(UUID subjectId) {
    return repository.findBySubjectId(subjectId);
  }
}
