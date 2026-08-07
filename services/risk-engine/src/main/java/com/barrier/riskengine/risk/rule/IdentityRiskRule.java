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
 *   <li>UNAVAILABLE → <b>revisão</b> (não foi possível confirmar a identidade)
 *   <li>VERIFIED → não aplicável
 * </ul>
 *
 * <p><b>Por que UNAVAILABLE força REVIEW:</b> antes esta regra só pontuava (150), o que caía na
 * banda LOW e resultava em APROVADO — ou seja, uma indisponibilidade do bureau virava aprovação
 * automática sem verificação alguma. Como a cadeia de bureaus só chega a UNAVAILABLE quando
 * <i>todos</i> os providers falharam ({@code IdentityService}), o motor estaria decidindo com a
 * informação mais importante ausente. Decidir sem insumo é fail-open; a decisão correta é
 * escalar para humano.
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
              "Identidade não confirmada: bureau indisponível na verificação",
              evidence,
              RiskRecommendation.REVIEW);
      case VERIFIED -> RiskResult.notApplicable("IDENTITY_OK");
    };
  }

  @Override
  public String code() {
    return "IDENTITY";
  }
}
