package com.barrier.riskengine.assessment.repository;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import com.barrier.riskengine.assessment.domain.AssessmentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/** Implementação JPA do repositório de domínio. */
@Repository
class AssessmentRepositoryImpl implements AssessmentRepository {

  private final AssessmentJpaRepository jpa;

  AssessmentRepositoryImpl(AssessmentJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Assessment save(Assessment assessment) {
    AssessmentEntity saved = jpa.save(AssessmentEntityMapper.toEntity(assessment));
    return AssessmentEntityMapper.toDomain(saved);
  }

  @Override
  public Optional<Assessment> findById(AssessmentId id) {
    return jpa.findById(id.value()).map(AssessmentEntityMapper::toDomain);
  }

  @Override
  public List<Assessment> findPending(int limit) {
    return jpa
        .findByStatusOrderByCreatedAtAsc(AssessmentStatus.EM_ANALISE, Limit.of(limit))
        .stream()
        .map(AssessmentEntityMapper::toDomain)
        .toList();
  }
}
