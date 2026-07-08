package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.CompanyProfile.Partner;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PjRiskRulesTest {

  private static final Clock FIXED =
      Clock.fixed(LocalDate.of(2026, 7, 8).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  private RiskContext context(CompanyProfile company) {
    return new RiskContext(
        "aid",
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "brasilapi", "ok"),
        ScreeningResult.of("aid", List.of()),
        company);
  }

  private CompanyProfile company(LocalDate opening, String cnae, List<Partner> partners) {
    return new CompanyProfile(opening, cnae, "desc", partners);
  }

  // --- NewCompanyRiskRule ---

  @Test
  void empresaRecemAbertaPontua() {
    var rule = new NewCompanyRiskRule(6, 150, FIXED);

    RiskResult r = rule.evaluate(context(company(LocalDate.of(2026, 5, 1), "1234567", List.of())));

    assertThat(r.triggered()).isTrue();
    assertThat(r.score()).isEqualTo(150);
    assertThat(r.recommendation()).isNull();
  }

  @Test
  void empresaAntigaNaoPontua() {
    var rule = new NewCompanyRiskRule(6, 150, FIXED);

    RiskResult r = rule.evaluate(context(company(LocalDate.of(2010, 1, 1), "1234567", List.of())));

    assertThat(r.triggered()).isFalse();
  }

  @Test
  void semPerfilNaoAplica() {
    var rule = new NewCompanyRiskRule(6, 150, FIXED);

    assertThat(rule.evaluate(context(null)).triggered()).isFalse();
  }

  // --- SensitiveCnaeRiskRule ---

  @Test
  void cnaeSensivelPontua() {
    var rule = new SensitiveCnaeRiskRule(Set.of("6619302", "6612605"), 200);

    RiskResult r = rule.evaluate(context(company(LocalDate.of(2010, 1, 1), "6619302", List.of())));

    assertThat(r.score()).isEqualTo(200);
    assertThat(r.evidences()).anyMatch(e -> e.contains("6619302"));
  }

  @Test
  void cnaeComumNaoPontua() {
    var rule = new SensitiveCnaeRiskRule(Set.of("6619302"), 200);

    assertThat(rule.evaluate(context(company(LocalDate.of(2010, 1, 1), "4712100", List.of()))).triggered())
        .isFalse();
  }

  // --- CorporateStructureRiskRule ---

  @Test
  void socioEstrangeiroEhAltaSeveridade() {
    var rule = new CorporateStructureRiskRule();
    var partners = List.of(new Partner("Foreign Holdings", true, true, "Sócio Estrangeiro"));

    RiskResult r = rule.evaluate(context(company(LocalDate.of(2010, 1, 1), "1234567", partners)));

    assertThat(r.severity()).isEqualTo(Severity.HIGH);
    assertThat(r.score()).isEqualTo(300); // estrangeiro (200) + PJ (100)
  }

  @Test
  void socioPjSemEstrangeiroEhMedia() {
    var rule = new CorporateStructureRiskRule();
    var partners = List.of(new Partner("Holding BR Ltda", true, false, "Sócio-Administrador"));

    RiskResult r = rule.evaluate(context(company(LocalDate.of(2010, 1, 1), "1234567", partners)));

    assertThat(r.severity()).isEqualTo(Severity.MEDIUM);
    assertThat(r.score()).isEqualTo(100);
  }

  @Test
  void quadroSoParaPessoasFisicasNacionaisNaoAplica() {
    var rule = new CorporateStructureRiskRule();
    var partners = List.of(new Partner("Fulano", false, false, "Sócio"));

    assertThat(rule.evaluate(context(company(LocalDate.of(2010, 1, 1), "1234567", partners))).triggered())
        .isFalse();
  }
}
