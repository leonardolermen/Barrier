package com.barrier.riskengine.rescreening.policy.repository;

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

/** Mapeamento JPA da trilha de decisões de reavaliação. */
@Entity
@Table(name = "reassessment_decisions")
@Getter(AccessLevel.PACKAGE)
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReassessmentDecisionEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "trigger_type", nullable = false, length = 30)
  private String triggerType;

  @Column(name = "trigger_detail", length = 200)
  private String triggerDetail;

  @Column(name = "reassessed", nullable = false)
  private boolean reassessed;

  @Column(name = "reason", length = 60)
  private String reason;

  @Column(name = "risk_level", length = 10)
  private String riskLevel;

  @Column(name = "assessment_id")
  private UUID assessmentId;

  @Column(name = "decided_at", nullable = false)
  private Instant decidedAt;
}
