package com.barrier.riskengine.replay.domain;

import java.util.List;

/**
 * O resultado de um replay: o que foi decidido, o que o motor de hoje diz, e <b>o que não deu para
 * saber</b>.
 *
 * @param replayedDecision {@code null} em {@link ReplayMode#AS_DECIDED} — ali nada é reexecutado
 * @param gaps insumos não reconstruídos. Lista vazia é a afirmação forte: <i>nada faltou</i>
 */
public record DecisionReplay(
    String assessmentId,
    ReplayMode mode,
    ReplayVerdict verdict,
    RecordedDecision recordedDecision,
    ReplayedDecision replayedDecision,
    ArithmeticCheck arithmetic,
    List<ReplayedRule> rules,
    List<ReconstructionGap> gaps) {

  public DecisionReplay {
    rules = List.copyOf(rules);
    gaps = List.copyOf(gaps);
  }
}
