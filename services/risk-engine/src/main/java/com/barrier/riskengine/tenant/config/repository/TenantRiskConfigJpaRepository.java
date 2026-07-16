package com.barrier.riskengine.tenant.config.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TenantRiskConfigJpaRepository extends JpaRepository<TenantRiskConfigEntity, UUID> {

  Optional<TenantRiskConfigEntity> findByTenantIdAndRuleCodeAndParamKey(
      String tenantId, String ruleCode, String paramKey);

  List<TenantRiskConfigEntity> findByTenantId(String tenantId);
}
