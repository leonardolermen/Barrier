package com.barrier.riskengine.identity.repository;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
class IdentityCheckRepositoryImpl implements IdentityCheckRepository {

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
}
