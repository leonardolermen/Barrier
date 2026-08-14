package com.barrier.riskengine.risk.rule;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.DivergentField;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import com.barrier.riskengine.risk.domain.model.RiskResult;
import com.barrier.riskengine.risk.rule.context.AssuranceSummary;
import com.barrier.riskengine.risk.rule.context.RiskContext;
import com.barrier.riskengine.risk.rule.interfaces.RiskRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Documentoscopia e biometria como fator de risco.
 *
 * <p>Desfechos deliberadamente separados, porque exigem condutas diferentes:
 *
 * <ul>
 *   <li><b>falha</b> (documento adulterado, face não confere, prova de vida reprovada) — sinal
 *       forte de fraude, pontua alto e força revisão humana. Não recusa sozinha: detector de
 *       documentoscopia erra com documento velho, plastificado ou de estado com layout antigo, e
 *       recusa automática por isso nega serviço a cliente legítimo sem ninguém olhar;
 *   <li><b>inconclusivo/indisponível</b> — pontua pouco e não opina. Foto tremida e provedor fora
 *       do ar não são fato sobre o cliente; tratá-los como falha seria transformar problema nosso
 *       em recusa dele;
 *   <li><b>muitas tentativas de biometria</b> — pontua mesmo quando a última passou. Quem testa
 *       artefato até vencer o detector deixa exatamente esse rastro, e olhar só a última tentativa
 *       apaga o único lugar onde ele aparece.
 * </ul>
 *
 * <p>Regra de <b>apetite</b>, não regulatória: nenhuma norma do Bacen exige biometria, então pode
 * ser desligada pelo registry como qualquer outra. O que a exige é a política do parceiro.
 */
@Component
public class IdentityAssuranceRiskRule implements RiskRule {

  private static final String RULE_CODE = "IDENTITY_ASSURANCE";

  private final int failScore;
  private final int inconclusiveScore;
  private final int retryScore;
  private final int retryThreshold;
  private final int divergenceScore;

  public IdentityAssuranceRiskRule(
      @Value("${barrier.risk.assurance.fail-score:600}") int failScore,
      @Value("${barrier.risk.assurance.inconclusive-score:100}") int inconclusiveScore,
      @Value("${barrier.risk.assurance.retry-score:200}") int retryScore,
      @Value("${barrier.risk.assurance.retry-threshold:3}") int retryThreshold,
      @Value("${barrier.risk.assurance.divergence-score:300}") int divergenceScore) {
    // 0 (ou negativo) faria `attempts >= retryThreshold` valer para toda avaliação, mesmo a que
    // nunca usou biometria (attempts=0) — o sistema inteiro cairia em REVIEW por config errada.
    // Antes desta task isso era inalcançável (assurance nascia sempre nulo); agora é uma linha de
    // configuração de distância, então falha cedo em vez de virar apetite de risco de fato.
    if (retryThreshold <= 0) {
      throw new IllegalArgumentException(
          "barrier.risk.assurance.retry-threshold precisa ser positivo, recebido: "
              + retryThreshold);
    }
    this.failScore = failScore;
    this.inconclusiveScore = inconclusiveScore;
    this.retryScore = retryScore;
    this.retryThreshold = retryThreshold;
    this.divergenceScore = divergenceScore;
  }

  @Override
  public RiskResult evaluate(RiskContext context) {
    AssuranceSummary assurance = context.assurance();
    // Ausência não é falha: o parceiro pode não usar esta etapa. Quem cobra a presença dela é o
    // gate de completude, com o desfecho de "pedir o que falta" — não o motor de risco, que
    // pontuaria como se algo tivesse dado errado.
    if (assurance == null) {
      return RiskResult.notApplicable(RULE_CODE);
    }

    List<String> evidence = new ArrayList<>();
    int score = 0;
    RiskRecommendation recommendation = null;

    if (assurance.documentFailed()) {
      score += failScore;
      recommendation = RiskRecommendation.REVIEW;
      evidence.add("documentoscopia:FAIL " + detailOf(assurance.document()));
    }
    if (assurance.biometricFailed()) {
      score += failScore;
      recommendation = RiskRecommendation.REVIEW;
      evidence.add("biometria:FAIL " + detailOf(assurance.biometric()));
    }
    if (assurance.anyInconclusive()) {
      score += inconclusiveScore;
      evidence.add("verificação inconclusiva ou provedor indisponível");
    }
    if (assurance.biometricAttempts() >= retryThreshold) {
      score += retryScore;
      recommendation = RiskRecommendation.REVIEW;
      evidence.add("biometria: " + assurance.biometricAttempts() + " tentativas");
    }
    // Nome pertence ao Subject, não ao cadastro — não há campo verificável equivalente a
    // VerifiableField para ele, então a divergência lida do documento vira sinal de risco aqui,
    // não campo faltando no gate de completude. AssuranceCheck.divergences nunca carrega o valor
    // declarado nem o extraído (PII) — só quais campos divergiram, e é isso (só o nome do campo,
    // nunca o valor) que entra na evidência: sem isso o analista que abre o EM_REVISAO não sabia
    // se a pontuação veio do nome ou da data de nascimento.
    if (assurance.document() != null && !assurance.document().divergences().isEmpty()) {
      score += divergenceScore;
      recommendation = RiskRecommendation.REVIEW;
      evidence.add(
          "documentoscopia: dado lido do documento diverge do cadastro declarado (campos: "
              + fieldNames(assurance.document().divergences())
              + ")");
    }

    if (score == 0) {
      return RiskResult.notApplicable(RULE_CODE);
    }
    return new RiskResult(
        RULE_CODE,
        score,
        recommendation == null ? Severity.LOW : Severity.HIGH,
        "Verificação de titularidade com apontamento",
        evidence,
        recommendation);
  }

  private static String detailOf(AssuranceCheck check) {
    return check.provider() + " ref " + check.providerReference();
  }

  /** Só os nomes dos campos (NAME/BIRTH_DATE), nunca o valor declarado nem o extraído. */
  private static String fieldNames(Set<DivergentField> fields) {
    return fields.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
  }

  @Override
  public String code() {
    return RULE_CODE;
  }
}
