package com.barrier.riskengine.assurance.repository;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class AssuranceCheckRepositoryImpl implements AssuranceCheckRepository {

  private static final String INSERT =
      "INSERT INTO identity_assurance_checks"
          + " (id, subject_id, tenant_id, kind, outcome, score, provider, provider_reference,"
          + " algorithm_version, submitted_hash, detail, checked_at, consent_reference,"
          + " consent_purpose, consent_granted_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private final JdbcTemplate jdbc;

  AssuranceCheckRepositoryImpl(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void save(AssuranceCheck c) {
    jdbc.update(
        INSERT,
        c.id(),
        c.subjectId(),
        c.tenantId(),
        c.kind().name(),
        c.outcome().name(),
        c.score(),
        c.provider(),
        c.providerReference(),
        c.algorithmVersion(),
        c.submittedHash(),
        c.detail(),
        Timestamp.from(c.checkedAt()),
        c.consent() == null ? null : c.consent().reference(),
        c.consent() == null ? null : c.consent().purpose(),
        c.consent() == null ? null : Timestamp.from(c.consent().grantedAt()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AssuranceCheck> findLatest(UUID subjectId, String tenantId, AssuranceKind kind) {
    return jdbc
        .query(
            "SELECT * FROM identity_assurance_checks WHERE subject_id = ? AND tenant_id = ?"
                + " AND kind = ? ORDER BY checked_at DESC LIMIT 1",
            (rs, i) -> map(rs),
            subjectId,
            tenantId,
            kind.name())
        .stream()
        .findFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public List<AssuranceCheck> findAll(UUID subjectId, String tenantId) {
    return jdbc.query(
        "SELECT * FROM identity_assurance_checks WHERE subject_id = ? AND tenant_id = ?"
            + " ORDER BY checked_at DESC",
        (rs, i) -> map(rs),
        subjectId,
        tenantId);
  }

  private static AssuranceCheck map(ResultSet rs) throws SQLException {
    int score = rs.getInt("score");
    String consentReference = rs.getString("consent_reference");
    Timestamp consentGrantedAt = rs.getTimestamp("consent_granted_at");
    AssuranceConsent consent =
        consentReference == null
            ? null
            : new AssuranceConsent(
                consentReference,
                rs.getString("consent_purpose"),
                consentGrantedAt == null ? null : consentGrantedAt.toInstant());
    return new AssuranceCheck(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("tenant_id"),
        AssuranceKind.valueOf(rs.getString("kind")),
        AssuranceOutcome.valueOf(rs.getString("outcome")),
        rs.wasNull() ? null : score,
        rs.getString("provider"),
        rs.getString("provider_reference"),
        rs.getString("algorithm_version"),
        rs.getString("submitted_hash"),
        rs.getString("detail"),
        rs.getTimestamp("checked_at").toInstant(),
        consent);
  }
}
