package com.barrier.riskengine.screening.domain;

/** Categoria de apontamento em listas restritivas. */
public enum MatchType {
  /** Pessoa Exposta Politicamente. */
  PEP,
  /** Sanções (ONU/OFAC/CGU, etc.). */
  SANCTION,
  /** Mídia adversa. */
  ADVERSE_MEDIA
}
