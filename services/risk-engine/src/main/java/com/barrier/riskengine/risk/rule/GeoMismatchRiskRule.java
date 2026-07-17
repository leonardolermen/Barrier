package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.geoip.client.GeoIpLookup;
import com.barrier.riskengine.geoip.client.GeoIpProvider;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.subject.profile.domain.SubjectProfile;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * IP de origem geolocalizado num estado diferente do endereço cadastrado — sinal de atenção
 * (VPN, proxy, ou apenas cliente viajando/mudando); não força recomendação sozinho.
 */
@Component
public class GeoMismatchRiskRule implements RiskRule {

  private final GeoIpProvider geoIpProvider;
  private final int score;

  public GeoMismatchRiskRule(
      GeoIpProvider geoIpProvider, @Value("${barrier.risk.geo-mismatch-score:80}") int score) {
    this.geoIpProvider = geoIpProvider;
    this.score = score;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    SubjectProfile profile = context.profile();
    if (context.ip() == null || profile == null || profile.address() == null) {
      return RiskResult.notApplicable("GEO_MISMATCH");
    }
    GeoIpLookup lookup = geoIpProvider.lookup(context.ip());
    String addressUf = profile.address().state();
    if (lookup.state() == null || addressUf == null || lookup.state().equalsIgnoreCase(addressUf)) {
      return RiskResult.notApplicable("GEO_MISMATCH");
    }
    return new RiskResult(
        "GEO_MISMATCH",
        score,
        Severity.LOW,
        "IP geolocalizado em " + lookup.state() + ", endereço cadastrado em " + addressUf,
        List.of("ip_uf:" + lookup.state(), "address_uf:" + addressUf),
        null);
  }

  @Override
  public String code() {
    return "GEO_MISMATCH";
  }
}
