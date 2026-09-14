package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.PolicyStatus;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicy;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import com.barrier.riskengine.riskpolicy.repository.interfaces.RiskPolicyRepository;
import java.time.Instant;
import java.time.Period;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ciclo de vida de {@link RiskPolicy}: criação compilada, ativação e arquivamento. */
class RiskPolicyServiceTest {

  private static final String TENANT = "acme";

  private final PolicyCompiler compiler = new PolicyCompiler(FieldCatalog.V1);
  private final InMemoryRiskPolicyRepository repository = new InMemoryRiskPolicyRepository();
  private final RiskPolicyService service = new RiskPolicyService(repository, compiler);

  private PolicyRule regraValida(String codigo) {
    Condition when =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            Operator.WITHIN_LAST,
            Literal.duration(Period.ofMonths(6)));
    return new PolicyRule(
        codigo, "empresa nova", when, 100, Severity.MEDIUM, RiskRecommendation.REVIEW);
  }

  private RiskPolicy criarDraft(String codigoDaRegra) {
    return service.createDraft(
        TENANT, PolicyDomain.ONBOARDING, List.of(regraValida(codigoDaRegra)), "ana@parceiro");
  }

  private PolicyRule regraComScoreNegativo() {
    Condition when =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            Operator.WITHIN_LAST,
            Literal.duration(Period.ofMonths(6)));
    return new PolicyRule("CUSTOM_AFROUXA", "x", when, -50, Severity.LOW, null);
  }

  @Test
  void createDraft_compila_antes_de_gravar_e_recusa_regra_que_viola_o_piso() {
    assertThatThrownBy(
            () ->
                service.createDraft(
                    TENANT,
                    PolicyDomain.ONBOARDING,
                    List.of(regraComScoreNegativo()),
                    "ana@parceiro"))
        .isInstanceOf(PolicyCompilationException.class);

    assertThat(repository.listByTenant(TENANT)).isEmpty();
  }

  @Test
  void createDraft_grava_versao_1_depois_versao_2_em_draft() {
    RiskPolicy v1 = criarDraft("CUSTOM_A");
    RiskPolicy v2 = criarDraft("CUSTOM_B");

    assertThat(v1.version()).isEqualTo(1);
    assertThat(v2.version()).isEqualTo(2);
    assertThat(v1.status()).isEqualTo(PolicyStatus.DRAFT);
    assertThat(v1.catalogVersion()).isEqualTo(FieldCatalog.VERSION);
    assertThat(v1.createdBy()).isEqualTo("ana@parceiro");
  }

  @Test
  void activate_arquiva_a_ativa_anterior_e_ativa_a_pedida_na_mesma_chamada() {
    RiskPolicy v1 = criarDraft("CUSTOM_A");
    service.activate(TENANT, v1.version(), "supervisor@parceiro");
    RiskPolicy v2 = criarDraft("CUSTOM_B");

    RiskPolicy ativada = service.activate(TENANT, v2.version(), "supervisor@parceiro");

    assertThat(ativada.status()).isEqualTo(PolicyStatus.ACTIVE);
    assertThat(ativada.activatedBy()).isEqualTo("supervisor@parceiro");
    assertThat(repository.findByTenantAndVersion(TENANT, v1.version()).orElseThrow().status())
        .isEqualTo(PolicyStatus.ARCHIVED);
  }

  @Test
  void activate_recusa_versao_que_nao_esta_em_draft() {
    RiskPolicy v1 = criarDraft("CUSTOM_A");
    service.activate(TENANT, v1.version(), "supervisor@parceiro");

    assertThatThrownBy(() -> service.activate(TENANT, v1.version(), "supervisor@parceiro"))
        .isInstanceOf(PolicyStateException.class)
        .hasMessageContaining("DRAFT");
  }

  @Test
  void activate_versao_inexistente_lanca_nao_encontrado() {
    assertThatThrownBy(() -> service.activate(TENANT, 999, "supervisor@parceiro"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void archive_arquiva_a_versao_pedida() {
    RiskPolicy v1 = criarDraft("CUSTOM_A");
    service.activate(TENANT, v1.version(), "supervisor@parceiro");

    RiskPolicy arquivada = service.archive(TENANT, v1.version());

    assertThat(arquivada.status()).isEqualTo(PolicyStatus.ARCHIVED);
    assertThat(repository.findByTenantAndVersion(TENANT, v1.version()).orElseThrow().status())
        .isEqualTo(PolicyStatus.ARCHIVED);
  }

  // --- dobra ------------------------------------------------------------------

  private static final class InMemoryRiskPolicyRepository implements RiskPolicyRepository {
    private final Map<UUID, RiskPolicy> byId = new HashMap<>();

    @Override
    public RiskPolicy create(RiskPolicy policy) {
      byId.put(policy.id(), policy);
      return policy;
    }

    @Override
    public Optional<RiskPolicy> findActive(String tenantId, PolicyDomain domain) {
      return byId.values().stream()
          .filter(
              p ->
                  p.tenantId().equals(tenantId)
                      && p.domain() == domain
                      && p.status() == PolicyStatus.ACTIVE)
          .findFirst();
    }

    @Override
    public Optional<RiskPolicy> findByTenantAndVersion(String tenantId, int version) {
      return byId.values().stream()
          .filter(p -> p.tenantId().equals(tenantId) && p.version() == version)
          .findFirst();
    }

    @Override
    public List<RiskPolicy> listByTenant(String tenantId) {
      return byId.values().stream().filter(p -> p.tenantId().equals(tenantId)).toList();
    }

    @Override
    public int nextVersion(String tenantId) {
      return byId.values().stream()
              .filter(p -> p.tenantId().equals(tenantId))
              .mapToInt(RiskPolicy::version)
              .max()
              .orElse(0)
          + 1;
    }

    @Override
    public void activate(UUID id, String activatedBy, Instant when) {
      RiskPolicy atual = require(id);
      byId.put(id, atual.activate(activatedBy, when));
    }

    @Override
    public void archive(UUID id, Instant when) {
      RiskPolicy atual = require(id);
      byId.put(id, atual.archive(when));
    }

    private RiskPolicy require(UUID id) {
      RiskPolicy p = byId.get(id);
      if (p == null) {
        throw new NoSuchElementException("Política não encontrada: " + id);
      }
      return p;
    }
  }
}
