package com.barrier.riskengine.identity.repository;

import com.barrier.riskengine.identity.domain.IdentityCheck;

final class IdentityCheckEntityMapper {

  private IdentityCheckEntityMapper() {}

  static IdentityCheckEntity toEntity(IdentityCheck c) {
    IdentityCheckEntity e = new IdentityCheckEntity();
    e.setId(c.id());
    e.setAssessmentId(c.assessmentId());
    e.setStatus(c.status());
    e.setProvider(c.provider());
    e.setDetail(c.detail());
    e.setCheckedAt(c.checkedAt());
    return e;
  }

  static IdentityCheck toDomain(IdentityCheckEntity e) {
    return new IdentityCheck(
        e.getId(),
        e.getAssessmentId(),
        e.getStatus(),
        e.getProvider(),
        e.getDetail(),
        e.getCheckedAt());
  }
}
