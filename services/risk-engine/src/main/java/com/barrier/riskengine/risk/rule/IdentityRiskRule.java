package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Risco derivado da verificação de identidade.
 *
 * <ul>
 *   <li>NOT_FOUND → bloqueio (documento inexistente no bureau)
 *   <li>MISMATCH → revisão (dados divergentes)
 *   <li>UNAVAILABLE → pontua (não foi possível confirmar), sem bloquear
 *   <li>VERIFIED → não aplicável
 * </ul>
 */
@Component
public class IdentityRiskRule implements RiskRule {

  @Override
  public RiskResult evaluate(RiskContext context) {
    List<String> evidence = List.of("bureau:" + context.identity().provider());
    return switch (context.identity().status()) {
      case NOT_FOUND ->
          new RiskResult(
              "IDENTITY_NOT_FOUND",
              900,
              Severity.CRITICAL,
              "Documento não encontrado no bureau",
              evidence,
              RiskRecommendation.REJECT);
      case MISMATCH ->
          new RiskResult(
              "IDENTITY_MISMATCH",
              400,
              Severity.HIGH,
              "Dados divergentes do bureau",
              evidence,
              RiskRecommendation.REVIEW);
      case UNAVAILABLE ->
          new RiskResult(
              "IDENTITY_UNAVAILABLE",
              150,
              Severity.MEDIUM,
              "Bureau indisponível na verificação",
              evidence,
              null);
      case VERIFIED -> RiskResult.notApplicable("IDENTITY_OK");
    };
  }
}
