package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsistencyRiskRuleTest {

  private RiskContext context(SubjectProfile profile) {
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "stub", "ok"),
        ScreeningResult.of("aid", List.of()),
        null,
        profile,
        null);
  }

  private SubjectProfile profileWith(String phone, String state) {
    SubjectProfile.Address address =
        state == null ? null : new SubjectProfile.Address("Rua X", "1", null, "Centro", "Cidade", state, "00000-000");
    return new SubjectProfile(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "default",
        null,
        null,
        null,
        null,
        null,
        address,
        phone,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        Instant.now(),
        Instant.now());
  }

  @Test
  void telefoneEEnderecoDaMesmaUfNaoPontua() {
    var rule = new ConsistencyRiskRule(60);

    RiskResult r = rule.evaluate(context(profileWith("11 91234-5678", "SP")));

    assertThat(r.triggered()).isFalse();
  }

  @Test
  void telefoneDeOutraUfPontua() {
    var rule = new ConsistencyRiskRule(60);

    RiskResult r = rule.evaluate(context(profileWith("21 91234-5678", "SP")));

    assertThat(r.triggered()).isTrue();
    assertThat(r.score()).isEqualTo(60);
    assertThat(r.evidences()).anyMatch(e -> e.contains("phone_uf:RJ"));
  }

  @Test
  void semPerfilNaoAplica() {
    var rule = new ConsistencyRiskRule(60);

    assertThat(rule.evaluate(context(null)).triggered()).isFalse();
  }

  @Test
  void semTelefoneNaoAplica() {
    var rule = new ConsistencyRiskRule(60);

    assertThat(rule.evaluate(context(profileWith(null, "SP"))).triggered()).isFalse();
  }

  @Test
  void semEnderecoNaoAplica() {
    var rule = new ConsistencyRiskRule(60);

    assertThat(rule.evaluate(context(profileWith("11 91234-5678", null))).triggered()).isFalse();
  }

  @Test
  void dddDesconhecidoNaoAplica() {
    var rule = new ConsistencyRiskRule(60);

    assertThat(rule.evaluate(context(profileWith("00 91234-5678", "SP"))).triggered()).isFalse();
  }
}
