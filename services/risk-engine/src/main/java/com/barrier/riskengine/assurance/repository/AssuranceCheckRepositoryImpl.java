package com.barrier.riskengine.assurance.repository;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceConsent;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.domain.DivergentField;
import com.barrier.riskengine.assurance.repository.interfaces.AssuranceCheckRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class AssuranceCheckRepositoryImpl implements AssuranceCheckRepository {

  private static final String INSERT =
      "INSERT INTO identity_assurance_checks"
          + " (id, subject_id, tenant_id, kind, outcome, score, provider, provider_reference,"
          + " algorithm_version, submitted_hash, detail, divergent_fields, checked_at,"
          + " consent_reference, consent_purpose, consent_granted_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

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
        c.divergences().isEmpty()
            ? null
            : c.divergences().stream().map(Enum::name).collect(Collectors.joining(",")),
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
    // rs.wasNull() reflete a ÚLTIMA coluna lida no ResultSet, não a coluna que se quer checar.
    // Passá-lo como argumento posicional do construtor é uma armadilha: os argumentos de Java são
    // avaliados da esquerda para a direita, então qualquer rs.getString(...) de coluna NOT NULL
    // entre o rs.getInt("score") e o rs.wasNull() (id, subject_id, tenant_id, kind, outcome, todos
    // acima na lista de argumentos) reseta a flag para false — e um score nulo silenciosamente
    // virava 0. Isolar a leitura em variável local, com o wasNull() checado imediatamente depois,
    // fecha essa lacuna.
    int rawScore = rs.getInt("score");
    Integer score = rs.wasNull() ? null : rawScore;
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
        score,
        rs.getString("provider"),
        rs.getString("provider_reference"),
        rs.getString("algorithm_version"),
        rs.getString("submitted_hash"),
        rs.getString("detail"),
        parseDivergences(rs.getString("divergent_fields")),
        rs.getTimestamp("checked_at").toInstant(),
        consent);
  }

  private static Set<DivergentField> parseDivergences(String raw) {
    if (raw == null || raw.isBlank()) {
      return Set.of();
    }
    Set<DivergentField> fields = EnumSet.noneOf(DivergentField.class);
    for (String name : raw.split(",")) {
      fields.add(DivergentField.valueOf(name));
    }
    return fields;
  }
}
