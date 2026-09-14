package com.barrier.riskengine.riskpolicy.repository;

import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.PolicyStatus;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicy;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicyNotFoundException;
import com.barrier.riskengine.riskpolicy.repository.interfaces.RiskPolicyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Implementação JPA do repositório de política; a árvore de regras vai para {@code rules_json}. */
@Repository
class RiskPolicyRepositoryImpl implements RiskPolicyRepository {

  private final RiskPolicyJpaRepository jpa;
  private final PolicyRuleJson rulesJson;

  RiskPolicyRepositoryImpl(RiskPolicyJpaRepository jpa, PolicyRuleJson rulesJson) {
    this.jpa = jpa;
    this.rulesJson = rulesJson;
  }

  @Override
  public RiskPolicy create(RiskPolicy policy) {
    RiskPolicyEntity e = new RiskPolicyEntity();
    e.setId(policy.id());
    e.setTenantId(policy.tenantId());
    e.setDomain(policy.domain());
    e.setVersion(policy.version());
    e.setStatus(policy.status());
    e.setCatalogVersion(policy.catalogVersion());
    e.setRulesJson(rulesJson.toJson(policy.rules()));
    e.setCreatedBy(policy.createdBy());
    e.setCreatedAt(policy.createdAt());
    e.setActivatedBy(policy.activatedBy());
    e.setActivatedAt(policy.activatedAt());
    e.setArchivedAt(policy.archivedAt());
    return toDomain(jpa.save(e));
  }

  @Override
  public Optional<RiskPolicy> findActive(String tenantId, PolicyDomain domain) {
    return jpa.findByTenantIdAndDomainAndStatus(tenantId, domain, PolicyStatus.ACTIVE)
        .map(this::toDomain);
  }

  @Override
  public Optional<RiskPolicy> findByTenantAndVersion(String tenantId, int version) {
    return jpa.findByTenantIdAndVersion(tenantId, version).map(this::toDomain);
  }

  @Override
  public List<RiskPolicy> listByTenant(String tenantId) {
    return jpa.findByTenantIdOrderByVersionAsc(tenantId).stream().map(this::toDomain).toList();
  }

  @Override
  public int nextVersion(String tenantId) {
    return jpa.maxVersion(tenantId) + 1;
  }

  @Override
  public void activate(UUID id, String activatedBy, Instant when) {
    RiskPolicyEntity e = requireById(id);
    e.setStatus(PolicyStatus.ACTIVE);
    e.setActivatedBy(activatedBy);
    e.setActivatedAt(when);
    jpa.save(e);
  }

  @Override
  public void archive(UUID id, Instant when) {
    RiskPolicyEntity e = requireById(id);
    e.setStatus(PolicyStatus.ARCHIVED);
    e.setArchivedAt(when);
    jpa.save(e);
    // Flush aqui não é otimização opcional -- é requisito do índice único parcial
    // `uq_risk_policies_uma_ativa` (uma ACTIVE por tenant/domínio, e um índice PARCIAL não pode
    // ser DEFERRABLE no Postgres). RiskPolicyService#activate arquiva a ativa atual e ativa a
    // nova na MESMA transação; as duas são mutações de entidade e o SQL só sai no flush, e a
    // ordem de flush do Hibernate dentro do mesmo contexto de persistência não segue
    // necessariamente a ordem de chamada do código. Sem forçar este flush antes de o chamador
    // seguir para o `activate`, o UPDATE que ativa a nova linha pode chegar ao banco antes do
    // UPDATE que arquiva a antiga -- e o índice rejeita com razão, porque naquele instante
    // existiriam duas ACTIVE de verdade. Não remover por "parecer redundante": é a ordem de
    // chegada ao banco que este índice exige, não a ordem lógica no código Java.
    jpa.flush();
  }

  private RiskPolicyEntity requireById(UUID id) {
    return jpa.findById(id).orElseThrow(() -> new RiskPolicyNotFoundException(id));
  }

  private RiskPolicy toDomain(RiskPolicyEntity e) {
    return new RiskPolicy(
        e.getId(),
        e.getTenantId(),
        e.getDomain(),
        e.getVersion(),
        e.getStatus(),
        e.getCatalogVersion(),
        rulesJson.fromJson(e.getRulesJson()),
        e.getCreatedBy(),
        e.getCreatedAt(),
        e.getActivatedBy(),
        e.getActivatedAt(),
        e.getArchivedAt());
  }
}
