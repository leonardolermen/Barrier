package com.barrier.riskengine.subject.profile.repository;

import com.barrier.riskengine.subject.profile.domain.FieldVerification;
import com.barrier.riskengine.subject.profile.domain.VerifiableField;
import com.barrier.riskengine.subject.profile.domain.VerificationChallenge;
import com.barrier.riskengine.subject.profile.domain.VerificationMethod;
import com.barrier.riskengine.subject.profile.repository.interfaces.FieldVerificationRepository;
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
class FieldVerificationRepositoryImpl implements FieldVerificationRepository {

  private static final String UPSERT_VERIFICATION =
      "INSERT INTO subject_field_verifications"
          + " (id, subject_id, tenant_id, field, method, verified_value, evidence, verified_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
          + " ON CONFLICT (subject_id, tenant_id, field) DO UPDATE SET"
          + " method = EXCLUDED.method, verified_value = EXCLUDED.verified_value,"
          + " evidence = EXCLUDED.evidence, verified_at = EXCLUDED.verified_at";

  private final JdbcTemplate jdbc;

  FieldVerificationRepositoryImpl(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void save(FieldVerification v) {
    jdbc.update(
        UPSERT_VERIFICATION,
        v.id(),
        v.subjectId(),
        v.tenantId(),
        v.field().name(),
        v.method().name(),
        v.verifiedValue(),
        v.evidence(),
        Timestamp.from(v.verifiedAt()));
  }

  @Override
  @Transactional(readOnly = true)
  public List<FieldVerification> findBySubjectAndTenant(UUID subjectId, String tenantId) {
    return jdbc.query(
        "SELECT * FROM subject_field_verifications WHERE subject_id = ? AND tenant_id = ?",
        (rs, i) -> toVerification(rs),
        subjectId,
        tenantId);
  }

  @Override
  @Transactional
  public void saveChallenge(VerificationChallenge c) {
    jdbc.update(
        "INSERT INTO verification_challenges"
            + " (id, subject_id, tenant_id, field, target, code_hash, attempts_left, expires_at,"
            + " consumed_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        c.id(),
        c.subjectId(),
        c.tenantId(),
        c.field().name(),
        c.target(),
        c.codeHash(),
        c.attemptsLeft(),
        Timestamp.from(c.expiresAt()),
        c.consumedAt() == null ? null : Timestamp.from(c.consumedAt()),
        Timestamp.from(c.createdAt()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<VerificationChallenge> findLatestChallenge(
      UUID subjectId, String tenantId, VerifiableField field) {
    return jdbc
        .query(
            "SELECT * FROM verification_challenges"
                + " WHERE subject_id = ? AND tenant_id = ? AND field = ?"
                + " ORDER BY created_at DESC LIMIT 1",
            (rs, i) -> toChallenge(rs),
            subjectId,
            tenantId,
            field.name())
        .stream()
        .findFirst();
  }

  @Override
  @Transactional
  public void updateChallenge(VerificationChallenge c) {
    jdbc.update(
        "UPDATE verification_challenges SET attempts_left = ?, consumed_at = ? WHERE id = ?",
        c.attemptsLeft(),
        c.consumedAt() == null ? null : Timestamp.from(c.consumedAt()),
        c.id());
  }

  private static FieldVerification toVerification(ResultSet rs) throws SQLException {
    return new FieldVerification(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("tenant_id"),
        VerifiableField.valueOf(rs.getString("field")),
        VerificationMethod.valueOf(rs.getString("method")),
        rs.getString("verified_value"),
        rs.getString("evidence"),
        rs.getTimestamp("verified_at").toInstant());
  }

  private static VerificationChallenge toChallenge(ResultSet rs) throws SQLException {
    Timestamp consumed = rs.getTimestamp("consumed_at");
    return new VerificationChallenge(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("tenant_id"),
        VerifiableField.valueOf(rs.getString("field")),
        rs.getString("target"),
        rs.getString("code_hash"),
        rs.getInt("attempts_left"),
        rs.getTimestamp("expires_at").toInstant(),
        consumed == null ? null : consumed.toInstant(),
        rs.getTimestamp("created_at").toInstant());
  }
}
