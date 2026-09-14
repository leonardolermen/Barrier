package com.barrier.riskengine.riskpolicy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.ContextInput;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.domain.tree.Condition;
import com.barrier.riskengine.riskpolicy.domain.tree.Literal;
import com.barrier.riskengine.riskpolicy.domain.tree.Operator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomPolicyRiskRuleTest {

  private static final Instant AGORA = Instant.parse("2026-09-01T00:00:00Z");

  private CustomPolicyRiskRule regra(int score, RiskRecommendation rec) {
    Condition c =
        new Condition.Comparison(
            FieldCatalog.V1.find("company.openingDate").orElseThrow(),
            Operator.WITHIN_LAST,
            Literal.duration(Period.ofMonths(6)));
    PolicyRule pr =
        new PolicyRule("CUSTOM_EMPRESA_NOVA", "Empresa nova", c, score, Severity.MEDIUM, rec);
    return new CustomPolicyRiskRule(pr, new ConditionEvaluator());
  }

  private RiskContext comAbertura(LocalDate quando) {
    CompanyProfile e = new CompanyProfile(quando, "6499-9/99", "d", List.of());
    return new RiskContext("a-1", "t-1", null, null, e, null, null, AGORA);
  }

  @Test
  void dispara_e_devolve_o_codigo_score_e_recomendacao_da_regra_do_parceiro() {
    RiskResult r =
        regra(150, RiskRecommendation.REVIEW).evaluate(comAbertura(LocalDate.of(2026, 7, 1)));

    assertThat(r.ruleCode()).isEqualTo("CUSTOM_EMPRESA_NOVA");
    assertThat(r.score()).isEqualTo(150);
    assertThat(r.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(r.triggered()).isTrue();
    assertThat(r.evidences()).isNotEmpty();
  }

  @Test
  void nao_dispara_devolve_nao_aplicavel() {
    RiskResult r =
        regra(150, RiskRecommendation.REVIEW).evaluate(comAbertura(LocalDate.of(2010, 1, 1)));

    assertThat(r.triggered()).isFalse();
    assertThat(r.score()).isZero();
    assertThat(r.recommendation()).isNull();
  }

  @Test
  void code_e_o_codigo_do_parceiro() {
    assertThat(regra(10, null).code()).isEqualTo("CUSTOM_EMPRESA_NOVA");
  }

  @Test
  void requires_vem_derivado_da_arvore() {
    assertThat(regra(10, null).requires()).containsExactly(ContextInput.COMPANY);
  }
}
