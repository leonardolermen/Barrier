package com.barrier.riskengine.history.repository;

import com.barrier.riskengine.history.domain.SubjectHistoryEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class SubjectHistoryRepositoryImpl implements SubjectHistoryRepository {

  private final SubjectHistoryJpaRepository jpa;

  SubjectHistoryRepositoryImpl(SubjectHistoryJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public SubjectHistoryEvent save(SubjectHistoryEvent event) {
    return toDomain(
        jpa.save(
            new SubjectHistoryEntity(
                event.id(),
                event.subjectId(),
                event.eventType(),
                event.detail(),
                event.occurredAt(),
                event.createdAt())));
  }

  @Override
  public List<SubjectHistoryEvent> findBySubjectId(UUID subjectId) {
    return jpa.findBySubjectId(subjectId).stream().map(this::toDomain).toList();
  }

  private SubjectHistoryEvent toDomain(SubjectHistoryEntity e) {
    return new SubjectHistoryEvent(
        e.getId(), e.getSubjectId(), e.getEventType(), e.getDetail(), e.getOccurredAt(),
        e.getCreatedAt());
  }
}
