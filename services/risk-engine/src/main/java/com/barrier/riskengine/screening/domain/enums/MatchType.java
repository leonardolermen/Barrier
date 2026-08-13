package com.barrier.riskengine.screening.domain.enums;

/** Categoria de apontamento em listas restritivas. */
public enum MatchType {
  /** Pessoa Exposta Politicamente. */
  PEP,
  /**
   * Sanção financeira/restritiva (OFAC, ONU): impedimento de relacionamento e de movimentação de
   * ativos.
   */
  SANCTION,
  /**
   * Inidoneidade ou impedimento de contratar com a administração pública (CEIS/CNEP).
   *
   * <p>Categoria própria porque <b>não é sanção financeira</b>: uma empresa inidônea em licitação
   * segue legalmente apta a manter relacionamento bancário. Tratá-la como sanção produzia recusa
   * automática — negação de serviço a quem a lei não impede de ser cliente. É informação
   * reputacional relevante para PLD-FT, e por isso continua na trilha; o que muda é o peso.
   */
  DEBARMENT,
  /** Mídia adversa. */
  ADVERSE_MEDIA
}
