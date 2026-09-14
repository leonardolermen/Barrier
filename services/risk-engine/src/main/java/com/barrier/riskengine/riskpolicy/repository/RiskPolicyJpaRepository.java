package com.barrier.riskengine.riskpolicy.repository;

import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.PolicyStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Package-private de propósito, mesmo padrão de {@code TenantRiskConfigJpaRepository}.
 *
 * <p>{@code save}/{@code saveAll}/{@code deleteAll} herdados de {@link JpaRepository} reescrevem
 * ou apagam linha sem passar pelo {@code PolicyCompiler} — é exatamente o segundo caminho de
 * escrita que {@code apenas_riskpolicyservice_escreve_em_risk_policy_repository} (no {@code
 * LayeredArchitectureTest}) fecha para {@code RiskPolicyRepository}. Aquela regra restringe
 * {@code create}/{@code activate}/{@code archive} de {@code RiskPolicyRepository} a {@code
 * RiskPolicyService}, mas isso só amarra quem chama pela interface que um chamador honesto usa —
 * antes, esta interface JPA era pública num pacote ({@code repository.interfaces}) separado do
 * seu único usuário ({@link RiskPolicyRepositoryImpl}), então nada além de convenção impedia
 * qualquer classe de {@code com.barrier.riskengine} de injetá-la direto e chamar {@code save}/
 * {@code deleteAll} sem nunca passar pelo compilador. Movida para o mesmo pacote da implementação
 * e sem {@code public}, o compilador Java é quem recusa — não há ArchUnit para contornar porque
 * não há como declarar a dependência fora deste pacote.
 */
interface RiskPolicyJpaRepository extends JpaRepository<RiskPolicyEntity, UUID> {

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
