package com.barrier.riskengine.mesa.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Mapeamento JPA do caso operacional. */
@Entity
@Table(name = "assessment_cases")
@Getter(AccessLevel.PACKAGE)
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssessmentCaseEntity {

  @Id
  @Column(name = "assessment_id", nullable = false)
  private UUID assessmentId;

  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "queue", nullable = false, length = 30)
  private String queue;

  @Column(name = "assigned_to", length = 120)
  private String assignedTo;

  @Column(name = "opened_at", nullable = false)
  private Instant openedAt;

  @Column(name = "closed_at")
  private Instant closedAt;
}
