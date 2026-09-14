package com.barrier.riskengine.riskpolicy.repository.interfaces;

import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.PolicyStatus;
import com.barrier.riskengine.riskpolicy.repository.RiskPolicyEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskPolicyJpaRepository extends JpaRepository<RiskPolicyEntity, UUID> {

  Optional<RiskPolicyEntity> findByTenantIdAndDomainAndStatus(
      String tenantId, PolicyDomain domain, PolicyStatus status);

  Optional<RiskPolicyEntity> findByTenantIdAndVersion(String tenantId, int version);

  List<RiskPolicyEntity> findByTenantIdOrderByVersionAsc(String tenantId);

  /**
   * Maior versão já usada por este tenant; 0 quando não há nenhuma -- {@code nextVersion} soma 1.
   */
  @Query(
      "SELECT COALESCE(MAX(p.version), 0) FROM RiskPolicyEntity p WHERE p.tenantId = :tenantId")
  int maxVersion(@Param("tenantId") String tenantId);
}
