package com.barrier.riskengine.replay.domain;

import com.barrier.riskengine.risk.domain.enums.RiskLevel;
import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * O que foi decidido, e com base em quê — a metade do dossiê que não depende de reexecutar nada.
 *
 * @param engineVersion versão do motor que tomou a decisão — primeiro eixo de versão
 * @param policyVersion versão da política custom do tenant que contribuiu regras a esta decisão;
 *     {@code null} quando o tenant não tinha política ativa então — segundo eixo de versão. Junto
 *     com o mesmo campo em {@link ReplayedDecision}, é o que separa "o motor mudou de opinião" de
 *     "o parceiro mudou a política": os dois eixos são reportados separadamente em vez de um único
 *     "mudou", para que a diferença fique atribuível a quem de fato a causou
 * @param watchlistVersions fonte → versão da lista consultada no screening. É o que torna um
 *     {@code CLEAR} verificável meses depois: a base é substituída todo dia
 * @param identityCheckId a verificação de identidade exata que sustentou a decisão (V028)
 * @param screeningResultId o screening exato que sustentou a decisão (V028)
 */
public record RecordedDecision(
    RiskLevel level,
    int score,
    RiskRecommendation recommendation,
    String engineVersion,
    Integer policyVersion,
    Instant decidedAt,
    UUID identityCheckId,
    UUID screeningResultId,
    Map<String, String> watchlistVersions) {

  public RecordedDecision {
    watchlistVersions = watchlistVersions == null ? Map.of() : Map.copyOf(watchlistVersions);
  }
}
