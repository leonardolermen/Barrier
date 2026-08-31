package com.barrier.riskengine.risk.domain.model;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.screening.domain.enums.MatchBasis;
import java.util.List;
import java.util.Objects;

/**
 * A aritmética da decisão: como um conjunto de resultados de regra vira score, banda e recomendação.
 *
 * <p>Função pura, extraída de {@code RiskScoringService} para ter <b>uma cópia só</b>. O replay de
 * decisão reconfere o desfecho gravado recalculando-o a partir dos resultados persistidos — e uma
 * reconferência que usasse a sua própria cópia da regra de agregação não conferiria nada: as duas
 * divergiriam com o tempo e a divergência apareceria como "a trilha está íntegra". É o mesmo motivo
 * pelo qual {@code PeriodicReassessmentJob} pré-filtra pelo menor prazo da {@code
 * ReassessmentPolicy} em vez de escrever os quatro prazos em SQL.
 *
 * @param level banda do score
 * @param recommendation recomendação consolidada (a mais severa entre a banda e os overrides)
 * @param totalScore pontuação total, limitada a {@link #MAX_SCORE}
 */
public record ScoreAggregation(
    RiskLevel level, RiskRecommendation recommendation, int totalScore) {

  public static final int MAX_SCORE = 1000;

  /** Agrega os resultados das regras que <b>dispararam</b>; regra que passou não entra. */
  public static ScoreAggregation of(List<RiskResult> triggered) {
    int total = Math.min(MAX_SCORE, triggered.stream().mapToInt(RiskResult::score).sum());
    RiskLevel level = band(total);
    RiskRecommendation recommendation =
        triggered.stream()
            .map(RiskResult::recommendation)
            .filter(Objects::nonNull)
            .reduce(bandRecommendation(level), RiskRecommendation::strongest);
    return new ScoreAggregation(level, recommendation, total);
  }

  public static RiskLevel band(int score) {
    if (score <= 199) {
      return RiskLevel.LOW;
    }
    if (score <= 499) {
      return RiskLevel.MEDIUM;
    }
    return score <= 799 ? RiskLevel.HIGH : RiskLevel.CRITICAL;
  }

  /**
   * O que a <b>banda</b> recomenda sozinha — e o teto disso é {@code REVIEW}, mesmo em CRITICAL.
   *
   * <p>A banda entra no {@code reduce} como valor inicial e disputa o {@code strongest} de igual
   * para igual com as regras, então antes ela podia agravar a decisão acima de tudo que qualquer
   * regra pediu. O efeito observado em produção-simulada: {@code PEP} (+300, pede REVIEW) somado a
   * {@code SANCTION_NAME_MATCH} (+500, pede REVIEW) dá 800, cruza o limiar de 799 por um ponto,
   * cai em CRITICAL e vira <b>reprovação automática</b>. Duas regras exigindo julgamento humano
   * produziam, somadas, uma recusa sem humano nenhum.
   *
   * <p>Isso anulava as duas decisões mais deliberadas do motor: {@link MatchBasis} existe para que
   * match por nome não reprove homônimo sem revisão, e PEP não é impedimento de relacionamento — é
   * gatilho de diligência reforçada (Circular BCB 3.978). Pior: {@code SCREENING_COVERAGE} (+300)
   * também pede REVIEW e também é somável, então um cliente podia ser reprovado em definitivo em
   * parte porque <i>a nossa importação de watchlist</i> falhou.
   *
   * <p>Somar sinais para agravar {@code APPROVE → REVIEW} é o cerne da abordagem baseada em risco e
   * continua valendo. O que não vale é somar incertezas até virar certeza: ambiguidade acumulada
   * segue sendo ambiguidade, e reprovação é terminal — não tem recurso no sistema.
   *
   * <p>Nada de reprovação legítima se perde: as únicas regras que pedem REJECT hoje
   * ({@code IDENTITY_NOT_FOUND}, 900; {@code SANCTION_HIT} por documento, 1000) já ultrapassam 799
   * sozinhas. A banda CRITICAL nunca foi o que descobre uma recusa correta — só acrescentava as
   * incorretas. O nível de risco continua sendo reportado como CRITICAL: o que muda é o que se faz
   * com ele.
   */
  public static RiskRecommendation bandRecommendation(RiskLevel level) {
    return switch (level) {
      case LOW, MEDIUM -> RiskRecommendation.APPROVE;
      case HIGH, CRITICAL -> RiskRecommendation.REVIEW;
    };
  }
}
