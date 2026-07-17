package com.barrier.riskengine.risk.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.geoip.client.GeoIpLookup;
import com.barrier.riskengine.geoip.client.GeoIpProvider;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityStatus;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.screening.domain.ScreeningResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GeoMismatchRiskRuleTest {

  private static GeoIpProvider providerReturning(GeoIpLookup lookup) {
    return ip -> lookup;
  }

  private SubjectProfile profileWithState(String state) {
    SubjectProfile.Address address =
        state == null ? null : new SubjectProfile.Address("Rua X", "1", null, "Centro", "Cidade", state, "00000-000");
    return new SubjectProfile(
        UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null, address, null, null,
        null, null, null, null, null, List.of(), Instant.now(), Instant.now());
  }

  private RiskContext context(String ip, SubjectProfile profile) {
    return new RiskContext(
        "aid",
        "default",
        IdentityCheck.create("aid", IdentityStatus.VERIFIED, "stub", "ok"),
        ScreeningResult.of("aid", List.of()),
        null,
        profile,
        ip,
        0);
  }

  @Test
  void ipEEnderecoNaMesmaUfNaoPontua() {
    var rule = new GeoMismatchRiskRule(providerReturning(new GeoIpLookup("BR", "SP")), 80);

    RiskResult r = rule.evaluate(context("200.1.2.3", profileWithState("SP")));

    assertThat(r.triggered()).isFalse();
  }

  @Test
  void ipDeOutraUfPontua() {
    var rule = new GeoMismatchRiskRule(providerReturning(new GeoIpLookup("BR", "RJ")), 80);

    RiskResult r = rule.evaluate(context("200.1.2.3", profileWithState("SP")));

    assertThat(r.triggered()).isTrue();
    assertThat(r.score()).isEqualTo(80);
    assertThat(r.evidences()).anyMatch(e -> e.contains("ip_uf:RJ"));
  }

  @Test
  void semIpNaoAplica() {
    var rule = new GeoMismatchRiskRule(providerReturning(new GeoIpLookup("BR", "RJ")), 80);

    assertThat(rule.evaluate(context(null, profileWithState("SP"))).triggered()).isFalse();
  }

  @Test
  void semPerfilNaoAplica() {
    var rule = new GeoMismatchRiskRule(providerReturning(new GeoIpLookup("BR", "RJ")), 80);

    assertThat(rule.evaluate(context("200.1.2.3", null)).triggered()).isFalse();
  }

  @Test
  void ipDesconhecidoNaoAplica() {
    var rule = new GeoMismatchRiskRule(providerReturning(GeoIpLookup.UNKNOWN), 80);

    assertThat(rule.evaluate(context("200.1.2.3", profileWithState("SP"))).triggered()).isFalse();
  }
}
