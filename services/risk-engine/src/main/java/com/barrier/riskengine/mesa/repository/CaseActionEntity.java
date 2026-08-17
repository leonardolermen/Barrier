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

/** Mapeamento JPA de uma ação manual. */
@Entity
@Table(name = "assessment_actions")
@Getter(AccessLevel.PACKAGE)
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaseActionEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "assessment_id", nullable = false)
  private UUID assessmentId;

  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "action_type", nullable = false, length = 30)
  private String actionType;

  @Column(name = "actor", nullable = false, length = 120)
  private String actor;

  @Column(name = "detail", length = 500)
  private String detail;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;
}
