package com.barrier.riskengine.screening.repository;

import com.barrier.riskengine.screening.domain.ScreeningHit;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.util.List;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Implementação JPA do repositório de screening. Os apontamentos ({@code hits}) são
 * serializados em JSON na coluna {@code hits_json} — a evidência fica auditável em um campo.
 */
@Repository
class ScreeningResultRepositoryImpl implements ScreeningResultRepository {

  private static final TypeReference<List<ScreeningHit>> HIT_LIST = new TypeReference<>() {};

  private final ScreeningResultJpaRepository jpa;
  private final ObjectMapper objectMapper;

  ScreeningResultRepositoryImpl(ScreeningResultJpaRepository jpa, ObjectMapper objectMapper) {
    this.jpa = jpa;
    this.objectMapper = objectMapper;
  }

  @Override
  public ScreeningResult save(ScreeningResult result) {
    ScreeningResultEntity e = new ScreeningResultEntity();
    e.setId(result.id());
    e.setAssessmentId(result.assessmentId());
    e.setStatus(result.status());
    e.setHitsJson(objectMapper.writeValueAsString(result.hits()));
    e.setCheckedAt(result.checkedAt());
    return toDomain(jpa.save(e));
  }

  @Override
  public List<ScreeningResult> findByAssessmentId(String assessmentId) {
    return jpa.findByAssessmentId(assessmentId).stream().map(this::toDomain).toList();
  }

  private ScreeningResult toDomain(ScreeningResultEntity e) {
    List<ScreeningHit> hits = objectMapper.readValue(e.getHitsJson(), HIT_LIST);
    return new ScreeningResult(
        e.getId(), e.getAssessmentId(), e.getStatus(), hits, e.getCheckedAt());
  }
}
