package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.CustomRules;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link CustomRuleSourceImpl}: fonte real de {@link CustomRuleSource}, lendo do repositório. */
@ExtendWith(MockitoExtension.class)
class CustomRuleSourceImplTest {

  private static final Instant AGORA = Instant.parse("2026-09-01T00:00:00Z");

  @Mock private RiskPolicyRepository repository;

  private CustomRuleSourceImpl source;

  @BeforeEach
  void setUp() {
    source = new CustomRuleSourceImpl(repository, new ConditionEvaluator());
  }

  private RiskContext ctx(String tenantId) {
    return new RiskContext("a-1", tenantId, null, null, null, null, null, AGORA);
  }

  private PolicyRule regra(String codigo) {
    Condition when =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            Operator.WITHIN_LAST,
            Literal.duration(Period.ofMonths(6)));
    return new PolicyRule(
        codigo, "empresa nova", when, 100, Severity.MEDIUM, RiskRecommendation.REVIEW);
  }

  private RiskPolicy politicaAtiva(String tenantId, int versao, PolicyRule... regras) {
    return new RiskPolicy(
        UUID.randomUUID(),
        tenantId,
        PolicyDomain.ONBOARDING,
        versao,
        PolicyStatus.ACTIVE,
        FieldCatalog.VERSION,
        List.of(regras),
        "ana@parceiro",
        AGORA,
        "supervisor@parceiro",
        AGORA,
        null);
  }

  @Test
  void tenant_sem_politica_devolve_NONE() {
    when(repository.findActive("t-sem", PolicyDomain.ONBOARDING)).thenReturn(Optional.empty());

    assertThat(source.forContext(ctx("t-sem"))).isEqualTo(CustomRules.NONE);
  }

  @Test
  void devolve_um_RiskRule_por_regra_da_politica_ativa() {
    RiskPolicy ativa = politicaAtiva("t-1", 1, regra("CUSTOM_A"), regra("CUSTOM_B"));
    when(repository.findActive("t-1", PolicyDomain.ONBOARDING)).thenReturn(Optional.of(ativa));

    List<RiskRule> regras = source.forContext(ctx("t-1")).rules();

    assertThat(regras).hasSize(2);
    assertThat(regras).extracting(RiskRule::code).containsExactlyInAnyOrder("CUSTOM_A", "CUSTOM_B");
  }

  @Test
  void devolve_a_versao_da_politica_que_produziu_as_regras() {
    RiskPolicy ativa = politicaAtiva("t-1", 3, regra("CUSTOM_A"));
    when(repository.findActive("t-1", PolicyDomain.ONBOARDING)).thenReturn(Optional.of(ativa));

    assertThat(source.forContext(ctx("t-1")).policyVersion()).isEqualTo(3);
  }

  /**
   * {@code findActive} nunca devolve uma versão arquivada -- é o contrato do repositório (provado
   * por {@code RiskPolicyRepositoryIntegrationTest}, no lado real da implementação JPA, filtrando
   * por {@code status = ACTIVE} na consulta). Do ponto de vista desta classe, "só existe versão
   * arquivada" e "tenant nunca teve política" são a mesma entrada: {@code Optional.empty()}. O
   * teste fica separado do de "tenant sem política" para documentar o cenário de negócio
   * explicitamente, não porque o código se ramifique para tratá-lo diferente.
   */
  @Test
  void politica_arquivada_nao_produz_regra() {
    when(repository.findActive("t-1", PolicyDomain.ONBOARDING)).thenReturn(Optional.empty());

    assertThat(source.forContext(ctx("t-1"))).isEqualTo(CustomRules.NONE);
  }
}
