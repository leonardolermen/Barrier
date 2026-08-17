package com.barrier.riskengine.riskstate.domain;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus;
import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import java.time.Instant;
import java.util.UUID;

/**
 * Risco corrente de um subject <b>sob um tenant</b> — a projeção viva ao lado da trilha imutável.
 *
 * <p>{@code risk_scores} guarda uma linha por avaliação e nunca é sobrescrito; é a evidência de
 * como cada decisão foi tomada. Esta projeção responde outra pergunta, que a trilha não responde
 * sem varredura: <i>qual é o risco deste cliente agora</i>.
 *
 * <p>Por que a chave inclui o tenant: a decisão de aceitar ou recusar é por tenant no assessment
 * (ADR-0011/ADR-0012). O mesmo subject pode estar aprovado num parceiro e reprovado em outro, e
 * um estado global teria que escolher um dos dois.
 *
 * @param evaluatedAt conclusão da avaliação que produziu este estado — é o relógio que ordena o
 *     upsert, e não o instante da gravação
 */
public record SubjectRiskState(
    UUID subjectId,
    String tenantId,
    RiskLevel level,
    int score,
    AssessmentStatus decision,
    UUID assessmentId,
    String engineVersion,
    Instant evaluatedAt,
    Instant updatedAt) {

  /**
   * Verdadeiro se {@code candidate} descreve uma avaliação mais recente que esta.
   *
   * <p>Empate conta como <b>não</b> mais recente: duas avaliações com o mesmo {@code evaluatedAt}
   * não têm critério de desempate honesto, e manter a primeira é estável (reprocessar o mesmo lote
   * não muda o resultado).
   */
  public boolean supersededBy(Instant candidate) {
    return candidate.isAfter(evaluatedAt);
  }
}
