package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.email.client.EmailLookup;
import com.barrier.riskengine.email.client.EmailProvider;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailDisposableRiskRuleTest {

  private SubjectProfile profileWithEmail(String email) {
    return new SubjectProfile(
        UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null, null, null, email,
        null, null, null, null, null, List.of(), Instant.now(), Instant.now());
  }

  private RiskContext context(SubjectProfile profile) {
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "stub", "ok"),
        ScreeningResult.of("aid", List.of()),
        null,
        profile,
        null,
        0,
        0);
  }

  @Test
  void emailDescartavelPontua() {
    var rule = new EmailDisposableRiskRule(e -> new EmailLookup(true), 90);

    RiskResult r = rule.evaluate(context(profileWithEmail("x@mailinator.com")));

    assertThat(r.triggered()).isTrue();
    assertThat(r.score()).isEqualTo(90);
  }

  @Test
  void emailComumNaoAplica() {
    EmailProvider provider = e -> new EmailLookup(false);
    var rule = new EmailDisposableRiskRule(provider, 90);

    assertThat(rule.evaluate(context(profileWithEmail("x@gmail.com"))).triggered()).isFalse();
  }

  @Test
  void semPerfilNaoAplica() {
    var rule = new EmailDisposableRiskRule(e -> new EmailLookup(true), 90);

    assertThat(rule.evaluate(context(null)).triggered()).isFalse();
  }

  @Test
  void semEmailNaoAplica() {
    var rule = new EmailDisposableRiskRule(e -> new EmailLookup(true), 90);

    assertThat(rule.evaluate(context(profileWithEmail(null))).triggered()).isFalse();
  }
}
