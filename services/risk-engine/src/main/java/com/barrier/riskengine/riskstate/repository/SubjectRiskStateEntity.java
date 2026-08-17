package com.barrier.riskengine.riskstate.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Mapeamento JPA da projeção de risco corrente. Chave composta (subject_id, tenant_id). */
@Entity
@Table(name = "subject_risk_state")
@IdClass(SubjectRiskStateEntity.Key.class)
@Getter(AccessLevel.PACKAGE)
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubjectRiskStateEntity {

  @Id
  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Id
  @Column(name = "tenant_id", nullable = false, length = 40)
  private String tenantId;

  @Column(name = "risk_level", nullable = false, length = 10)
  private String riskLevel;

  @Column(name = "risk_score", nullable = false)
  private int riskScore;

  @Column(name = "decision", nullable = false, length = 30)
  private String decision;

  @Column(name = "assessment_id", nullable = false)
  private UUID assessmentId;

  @Column(name = "engine_version", length = 40)
  private String engineVersion;

  @Column(name = "evaluated_at", nullable = false)
  private Instant evaluatedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Chave composta; exigida pelo {@code @IdClass}. */
  @Getter(AccessLevel.PACKAGE)
  @Setter(AccessLevel.PACKAGE)
  @NoArgsConstructor(access = AccessLevel.PUBLIC)
  public static class Key implements Serializable {

    private UUID subjectId;
    private String tenantId;

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key other)) {
        return false;
      }
      return java.util.Objects.equals(subjectId, other.subjectId)
          && java.util.Objects.equals(tenantId, other.tenantId);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(subjectId, tenantId);
    }
  }
}
