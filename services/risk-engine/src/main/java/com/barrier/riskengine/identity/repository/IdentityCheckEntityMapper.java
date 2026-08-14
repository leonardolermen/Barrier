package com.barrier.riskengine.identity.repository;

import com.barrier.riskengine.identity.domain.IdentityCheck;

final class IdentityCheckEntityMapper {

  private IdentityCheckEntityMapper() {}

  static IdentityCheckEntity toEntity(IdentityCheck c) {
    IdentityCheckEntity e = new IdentityCheckEntity();
    e.setId(c.id());
    e.setAssessmentId(c.assessmentId());
    e.setTenantId(c.tenantId());
    e.setDocumentType(c.documentType());
    e.setDocumentDigits(c.documentDigits());
    e.setName(c.name());
    e.setReusedFromId(c.reusedFromId());
    e.setStatus(c.status());
    e.setProvider(c.provider());
    e.setDetail(c.detail());
    e.setCheckedAt(c.checkedAt());
    e.setProviderReference(c.providerReference());
    e.setRawResponse(c.rawResponse());
    return e;
  }

  static IdentityCheck toDomain(IdentityCheckEntity e) {
    return new IdentityCheck(
        e.getId(),
        e.getAssessmentId(),
        e.getTenantId(),
        e.getDocumentType(),
        e.getDocumentDigits(),
        e.getName(),
        e.getStatus(),
        e.getProvider(),
        e.getDetail(),
        e.getCheckedAt(),
        e.getProviderReference(),
        e.getRawResponse(),
        e.getReusedFromId());
  }
}
