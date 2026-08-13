package com.barrier.riskengine.assurance;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.IdentityAssuranceRiskRule;
import com.barrier.riskengine.risk.rule.context.AssuranceSummary;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityAssuranceRiskRuleTest {

  private final IdentityAssuranceRiskRule rule =
      new IdentityAssuranceRiskRule(600, 100, 200, 3, 300);

  private static AssuranceCheck check(AssuranceKind kind, AssuranceOutcome outcome) {
    return new AssuranceCheck(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "t1",
        kind,
        outcome,
        outcome == AssuranceOutcome.PASS ? 98 : 10,
        "provedor",
        "ref-1",
        "modelo/2.1",
        "hash",
        "detalhe",
        Instant.now(),
        null);
  }

  private static RiskContext contexto(AssuranceSummary assurance) {
    return new RiskContext("a1", "t1", null, null, null, null, assurance);
  }

  /** Parceiro que não usa a etapa não pode ser punido por ela. */
  @Test
  void ausenciaDeVerificacaoNaoPontua() {
    assertThat(rule.evaluate(contexto(null)).triggered()).isFalse();
  }

  @Test
  void tudoAprovadoNaoPontua() {
    AssuranceSummary ok =
        new AssuranceSummary(
            check(AssuranceKind.DOCUMENT, AssuranceOutcome.PASS),
            check(AssuranceKind.BIOMETRIC, AssuranceOutcome.PASS),
            1);

    assertThat(rule.evaluate(contexto(ok)).triggered()).isFalse();
  }

  /**
   * Documento adulterado é sinal forte, mas não recusa sozinho: detector erra com documento velho
   * ou de layout antigo, e recusa automática negaria serviço a cliente legítimo sem ninguém olhar.
   */
  @Test
  void documentoAdulteradoForcaRevisaoENaoRecusa() {
    AssuranceSummary falha =
        new AssuranceSummary(check(AssuranceKind.DOCUMENT, AssuranceOutcome.FAIL), null, 0);

    RiskResult result = rule.evaluate(contexto(falha));

    assertThat(result.triggered()).isTrue();
    assertThat(result.score()).isEqualTo(600);
    assertThat(result.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(result.recommendation()).isNotEqualTo(RiskRecommendation.REJECT);
  }

  @Test
  void provaDeVidaReprovadaForcaRevisao() {
    AssuranceSummary falha =
        new AssuranceSummary(null, check(AssuranceKind.BIOMETRIC, AssuranceOutcome.FAIL), 1);

    assertThat(rule.evaluate(contexto(falha)).recommendation())
        .isEqualTo(RiskRecommendation.REVIEW);
  }

  /** Foto tremida e provedor fora do ar não são fato sobre o cliente. */
  @Test
  void inconclusivoPontuaPoucoENaoOpina() {
    AssuranceSummary inconclusivo =
        new AssuranceSummary(check(AssuranceKind.DOCUMENT, AssuranceOutcome.INCONCLUSIVE), null, 0);

    RiskResult result = rule.evaluate(contexto(inconclusivo));

    assertThat(result.score()).isEqualTo(100);
    assertThat(result.recommendation()).isNull();
  }

  @Test
  void provedorIndisponivelNaoViraRecusa() {
    AssuranceSummary indisponivel =
        new AssuranceSummary(null, check(AssuranceKind.BIOMETRIC, AssuranceOutcome.UNAVAILABLE), 1);

    assertThat(rule.evaluate(contexto(indisponivel)).recommendation()).isNull();
  }

  /**
   * O rastro que só existe no histórico: quem testa artefato até vencer o detector acaba passando
   * na última tentativa, e olhar só ela apaga o sinal.
   */
  @Test
  void muitasTentativasDeBiometriaPontuamMesmoComAUltimaAprovada() {
    AssuranceSummary insistente =
        new AssuranceSummary(null, check(AssuranceKind.BIOMETRIC, AssuranceOutcome.PASS), 4);

    RiskResult result = rule.evaluate(contexto(insistente));

    assertThat(result.triggered()).isTrue();
    assertThat(result.score()).isEqualTo(200);
    assertThat(result.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(result.evidences()).anySatisfy(e -> assertThat(e).contains("4 tentativas"));
  }
}
