package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.phone.client.PhoneLookup;
import com.barrier.riskengine.phone.client.PhoneProvider;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PhoneVoipRiskRuleTest {

  private SubjectProfile profileWithPhone(String phone) {
    return new SubjectProfile(
        UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null, null, phone, null,
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
        0,
        "CPF",
        "11144477735",
        List.of());
  }

  @Test
  void telefoneVoipPontua() {
    var rule = new PhoneVoipRiskRule(ph -> new PhoneLookup(true), 50);

    RiskResult r = rule.evaluate(context(profileWithPhone("11912345678")));

    assertThat(r.triggered()).isTrue();
    assertThat(r.score()).isEqualTo(50);
  }

  @Test
  void telefoneNaoVoipNaoAplica() {
    PhoneProvider provider = ph -> new PhoneLookup(false);
    var rule = new PhoneVoipRiskRule(provider, 50);

    assertThat(rule.evaluate(context(profileWithPhone("11912345678"))).triggered()).isFalse();
  }

  @Test
  void semPerfilNaoAplica() {
    var rule = new PhoneVoipRiskRule(ph -> new PhoneLookup(true), 50);

    assertThat(rule.evaluate(context(null)).triggered()).isFalse();
  }

  @Test
  void semTelefoneNaoAplica() {
    var rule = new PhoneVoipRiskRule(ph -> new PhoneLookup(true), 50);

    assertThat(rule.evaluate(context(profileWithPhone(null))).triggered()).isFalse();
  }
}
