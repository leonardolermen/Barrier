package com.barrier.riskengine.identity.repository;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.barrier.riskengine.identity.repository.interfaces.IdentityCheckJpaRepository;
import com.barrier.riskengine.identity.repository.interfaces.IdentityCheckRepository;
import org.springframework.stereotype.Repository;

@Repository
class IdentityCheckRepositoryImpl implements IdentityCheckRepository {

  /**
   * Desfechos que podem ser reaproveitados. {@code UNAVAILABLE} fica de fora: reusá-lo congelaria
   * uma indisponibilidade passada como se fosse resposta do bureau, e a avaliação seguinte
   * herdaria um REVIEW que talvez não fosse mais verdade.
   */
  private static final Set<IdentityStatus> REUSABLE =
      Set.of(
          IdentityStatus.VERIFIED,
          IdentityStatus.NOT_FOUND,
          IdentityStatus.MISMATCH,
          IdentityStatus.DECEASED);

  private final IdentityCheckJpaRepository jpa;

  IdentityCheckRepositoryImpl(IdentityCheckJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public IdentityCheck save(IdentityCheck check) {
    return IdentityCheckEntityMapper.toDomain(jpa.save(IdentityCheckEntityMapper.toEntity(check)));
  }

  @Override
  public List<IdentityCheck> findByAssessmentId(String assessmentId) {
    return jpa.findByAssessmentId(assessmentId).stream()
        .map(IdentityCheckEntityMapper::toDomain)
        .toList();
  }

  @Override
  public Optional<IdentityCheck> findReusable(
      String tenantId, String documentType, String documentDigits, String name, Instant notBefore) {
    return jpa.findReusable(tenantId, documentType, documentDigits, name, notBefore, REUSABLE)
        .map(IdentityCheckEntityMapper::toDomain);
  }
}
