package com.barrier.riskengine.behavior.repository;

import com.barrier.riskengine.behavior.domain.BehaviorEvent;
import com.barrier.riskengine.behavior.repository.interfaces.BehaviorEventJpaRepository;
import com.barrier.riskengine.behavior.repository.interfaces.BehaviorEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/** Implementação JPA do acervo comportamental. */
@Repository
class BehaviorEventRepositoryImpl implements BehaviorEventRepository {

  private final BehaviorEventJpaRepository jpa;

  BehaviorEventRepositoryImpl(BehaviorEventJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<BehaviorEvent> append(BehaviorEvent event) {
    // Checagem antes da escrita evita o caso comum (reenvio) sem depender de exceção; o catch
    // cobre a corrida entre duas ingestões simultâneas do mesmo evento, que o UNIQUE resolve.
    if (jpa.existsByTenantIdAndSourceEventId(event.tenantId(), event.sourceEventId())) {
      return Optional.empty();
    }
    BehaviorEventEntity e = new BehaviorEventEntity();
    e.setId(event.id());
    e.setTenantId(event.tenantId());
    e.setSubjectId(event.subjectId());
    e.setEventType(event.eventType());
    e.setOccurredAt(event.occurredAt());
    e.setReceivedAt(event.receivedAt());
    e.setPayload(event.payload());
    e.setSourceEventId(event.sourceEventId());
    try {
      jpa.save(e);
    } catch (DataIntegrityViolationException duplicado) {
      return Optional.empty();
    }
    return Optional.of(event);
  }

  @Override
  public List<BehaviorEvent> findRecent(UUID subjectId, String tenantId, Instant since, int limit) {
    return jpa
        .findBySubjectIdAndTenantIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
            subjectId, tenantId, since, Limit.of(limit))
        .stream()
        .map(BehaviorEventRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public long countByTypeSince(
      UUID subjectId, String tenantId, String eventType, Instant since) {
    return jpa.countBySubjectIdAndTenantIdAndEventTypeAndOccurredAtGreaterThanEqual(
        subjectId, tenantId, eventType, since);
  }

  private static BehaviorEvent toDomain(BehaviorEventEntity e) {
    return new BehaviorEvent(
        e.getId(),
        e.getTenantId(),
        e.getSubjectId(),
        e.getEventType(),
        e.getOccurredAt(),
        e.getReceivedAt(),
        e.getPayload(),
        e.getSourceEventId());
  }
}
