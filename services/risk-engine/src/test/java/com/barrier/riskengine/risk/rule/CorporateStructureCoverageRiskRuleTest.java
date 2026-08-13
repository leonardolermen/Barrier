package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorporateStructureCoverageRiskRuleTest {

  private final CorporateStructureCoverageRiskRule rule = new CorporateStructureCoverageRiskRule();

  private RiskResult evaluate(CompanyProfile company, IdentityCheck identity) {
    return rule.evaluate(new RiskContext("aid", "default", identity, null, company, null, null));
  }

  @Test
  void naoSeAplicaParaPf() {
    // PF nunca traz CompanyProfile — a regra fica inerte sem precisar checar tipo de documento.
    assertThat(evaluate(null, null).triggered()).isFalse();
  }

  @Test
  void naoSeAplicaQuandoQsaVeioPreenchido() {
    CompanyProfile company =
        new CompanyProfile(
            LocalDate.of(2020, 1, 1),
            "6201-5/01",
            "Desenvolvimento de software",
            List.of(new CompanyProfile.Partner("Jose Antonio da Silva", false, false, "Sócio")));

    assertThat(evaluate(company, null).triggered()).isFalse();
  }

  /**
   * Regressão do gap desta branch: BigBoost confirma a empresa (basic_data) mas não traz QSA —
   * CompanyProfile chega com sócios vazios e, sem esta regra, nada registrava que o KYB não rodou.
   */
  @Test
  void forcaRevisaoQuandoEmpresaConfirmadaSemQsa() {
    CompanyProfile company = new CompanyProfile(LocalDate.of(2020, 1, 1), "6201-5/01", "TI", List.of());
    IdentityCheck identity =
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "bigboost", "confirmado");

    RiskResult result = evaluate(company, identity);

    assertThat(result.triggered()).isTrue();
    assertThat(result.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(result.evidences()).containsExactly("bureau:bigboost");
  }

  @Test
  void evidenciaNaoQuebraSemIdentityCheck() {
    CompanyProfile company = new CompanyProfile(null, null, null, List.of());

    RiskResult result = evaluate(company, null);

    assertThat(result.evidences()).containsExactly("bureau:desconhecido");
  }

  @Test
  void codigoEstavelDaFamilia() {
    assertThat(rule.code()).isEqualTo("CORPORATE_STRUCTURE_COVERAGE");
  }
}
