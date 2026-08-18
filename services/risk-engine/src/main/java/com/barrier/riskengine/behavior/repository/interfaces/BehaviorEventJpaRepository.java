package com.barrier.riskengine.behavior.repository.interfaces;

import com.barrier.riskengine.behavior.repository.BehaviorEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BehaviorEventJpaRepository extends JpaRepository<BehaviorEventEntity, UUID> {

  boolean existsByTenantIdAndSourceEventId(String tenantId, String sourceEventId);

  List<BehaviorEventEntity> findBySubjectIdAndTenantIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
      UUID subjectId, String tenantId, Instant since, Limit limit);

  long countBySubjectIdAndTenantIdAndEventTypeAndOccurredAtGreaterThanEqual(
      UUID subjectId, String tenantId, String eventType, Instant since);
}
