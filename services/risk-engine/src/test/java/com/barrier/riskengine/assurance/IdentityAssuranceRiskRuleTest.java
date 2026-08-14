package com.barrier.riskengine.assurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import com.barrier.riskengine.assurance.domain.AssuranceOutcome;
import com.barrier.riskengine.assurance.domain.DivergentField;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.IdentityAssuranceRiskRule;
import com.barrier.riskengine.risk.rule.context.AssuranceSummary;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityAssuranceRiskRuleTest {

  private final IdentityAssuranceRiskRule rule =
      new IdentityAssuranceRiskRule(600, 100, 200, 3, 300);

  private static AssuranceCheck check(AssuranceKind kind, AssuranceOutcome outcome) {
    return check(kind, outcome, Set.of());
  }

  private static AssuranceCheck check(
      AssuranceKind kind, AssuranceOutcome outcome, Set<DivergentField> divergences) {
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
        divergences,
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

  /**
   * PENDING (PIN emitido, biometria assíncrona sem desfecho ainda) não é FAIL/INCONCLUSIVE/
   * UNAVAILABLE nem PASS — a regra não pode confundi-lo com nenhum dos quatro. É "ainda não há
   * verificação", o mesmo tratamento de {@code assurance == null}, não um quinto sinal de risco.
   */
  @Test
  void biometriaPendenteNaoPontua() {
    AssuranceSummary pendente =
        new AssuranceSummary(
            check(AssuranceKind.DOCUMENT, AssuranceOutcome.PASS),
            check(AssuranceKind.BIOMETRIC, AssuranceOutcome.PENDING),
            1);

    RiskResult result = rule.evaluate(contexto(pendente));

    assertThat(result.triggered()).isFalse();
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

  /**
   * Nome/nascimento lidos do documento divergentes do declarado pontuam e forçam revisão — sinal
   * de possível fraude, não campo faltando. A evidência nunca carrega os valores declarado ou
   * extraído (PII), só que houve divergência.
   */
  @Test
  void documentoComCadastroDivergentePontuaEForcaRevisao() {
    AssuranceSummary divergente =
        new AssuranceSummary(
            check(AssuranceKind.DOCUMENT, AssuranceOutcome.PASS, Set.of(DivergentField.NAME)),
            null,
            0);

    RiskResult result = rule.evaluate(contexto(divergente));

    assertThat(result.triggered()).isTrue();
    assertThat(result.score()).isEqualTo(300);
    assertThat(result.recommendation()).isEqualTo(RiskRecommendation.REVIEW);
    assertThat(result.evidences())
        .noneMatch(e -> e.toUpperCase().contains("MARIA") || e.matches(".*\\d{4}-\\d{2}-\\d{2}.*"));
  }

  @Test
  void documentSemDivergenciaNaoPontuaPorIsso() {
    AssuranceSummary ok =
        new AssuranceSummary(check(AssuranceKind.DOCUMENT, AssuranceOutcome.PASS, Set.of()), null, 0);

    assertThat(rule.evaluate(contexto(ok)).triggered()).isFalse();
  }

  /**
   * Este é o caso que virou maioria depois da Task 7: o {@code AssessmentProcessor} passou a
   * montar o {@code AssuranceSummary} sempre, mesmo quando o parceiro nunca submeteu
   * documentoscopia/biometria — o que antes chegava como {@code contexto(null)} agora chega como
   * um summary vazio. A segurança inteira depende do portão {@code if (score == 0) return
   * notApplicable}: sem este teste, mover esse {@code return} para fora do {@code if}, ou fazer a
   * regra sempre devolver evidência, quebraria em silêncio para toda avaliação sem assurance.
   */
  @Test
  void summaryVazioNaoPontuaNemOpina() {
    AssuranceSummary vazio = new AssuranceSummary(null, null, 0);

    RiskResult result = rule.evaluate(contexto(vazio));

    assertThat(result.triggered()).isFalse();
    assertThat(result.score()).isZero();
    assertThat(result.recommendation()).isNull();
    assertThat(result.evidences()).isEmpty();
  }

  /**
   * {@code 0} (ou negativo) faria {@code attempts >= retryThreshold} valer para toda avaliação,
   * mesmo quem nunca usou biometria ({@code attempts=0}) — o sistema inteiro cairia em REVIEW por
   * um erro de configuração. Antes da Task 7 isso era inalcançável (assurance nascia sempre
   * nulo); agora é uma linha de config de distância, então o construtor falha cedo.
   */
  @Test
  void retryThresholdZeroOuNegativoRejeitaNoConstrutor() {
    assertThatThrownBy(() -> new IdentityAssuranceRiskRule(600, 100, 200, 0, 300))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IdentityAssuranceRiskRule(600, 100, 200, -1, 300))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
