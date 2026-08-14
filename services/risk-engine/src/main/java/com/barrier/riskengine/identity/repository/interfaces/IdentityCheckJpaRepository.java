package com.barrier.riskengine.identity.repository.interfaces;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.identity.repository.IdentityCheckEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdentityCheckJpaRepository extends JpaRepository<IdentityCheckEntity, UUID> {

  List<IdentityCheckEntity> findByAssessmentId(String assessmentId);

  @Query(
      """
      SELECT c FROM IdentityCheckEntity c
       WHERE c.tenantId = :tenantId
         AND c.documentType = :documentType
         AND c.documentDigits = :documentDigits
         AND c.name = :name
         AND c.checkedAt >= :notBefore
         AND c.reusedFromId IS NULL
         AND c.status IN :statuses
       ORDER BY c.checkedAt DESC
       LIMIT 1
      """)
  Optional<IdentityCheckEntity> findReusable(
      @Param("tenantId") String tenantId,
      @Param("documentType") String documentType,
      @Param("documentDigits") String documentDigits,
      @Param("name") String name,
      @Param("notBefore") Instant notBefore,
      @Param("statuses") Collection<IdentityStatus> statuses);
}
