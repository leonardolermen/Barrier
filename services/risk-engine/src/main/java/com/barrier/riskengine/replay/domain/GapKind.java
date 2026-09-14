package com.barrier.riskengine.replay.domain;

/** Por que um insumo da decisão não pôde ser reconstruído. */
public enum GapKind {

  /**
   * {@code CompanyProfile} (abertura, CNAE, QSA) é transiente: alimenta as regras e é descartado.
   * Só chega ao banco em {@code subject_profiles}, que é mutável e não versionado.
   */
  COMPANY_NOT_PERSISTED,

  /**
   * O cadastro foi alterado depois desta decisão, e {@code subject_profiles} não guarda histórico —
   * então o que se lê hoje não é o que a decisão viu.
   *
   * <p>Versionar o cadastro é dado pessoal a mais sob retenção de 10 anos e criptografia em repouso,
   * e a decisão registrada no projeto é resolver isso <b>junto</b> com aquele item, não antes.
   */
  PROFILE_CHANGED_SINCE,

  /**
   * O resumo de documentoscopia/biometria não é reconstruível: {@code AssuranceSummary.attempts} é
   * uma contagem sobre janela que termina <b>agora</b>, não no instante da decisão.
   */
  ASSURANCE_WINDOW_RELATIVE,

  /** {@code risk_scores.identity_check_id} nulo (decisão anterior à V028) ou linha ausente. */
  IDENTITY_EVIDENCE_MISSING,

  /** {@code risk_scores.screening_result_id} nulo (decisão anterior à V028) ou linha ausente. */
  SCREENING_EVIDENCE_MISSING,

  /**
   * {@code evaluated_json} ausente (decisão anterior à V028): só as regras que dispararam foram
   * gravadas, então não há como provar que as demais rodaram e passaram.
   */
  EVALUATED_TRAIL_ABSENT,

  /**
   * A <b>autoria</b> da política vigente não é apurável para ao menos uma regra: há histórico, mas
   * todo posterior à decisão, e a V033 grava o estado novo de cada mudança — não o anterior.
   *
   * <p>Único {@link GapKind} que <b>não</b> degrada o veredito, e a distinção é deliberada. O que
   * falta aqui é quem definiu a política, não o que o motor decidiu: o desfecho de cada regra,
   * inclusive as suprimidas, continua gravado em {@code evaluated_json}, e a aritmética continua
   * conferindo. Degradar por isto rebaixaria praticamente todo replay antigo — qualquer regra
   * alterada uma vez, depois, produz esta lacuna para toda decisão anterior —, e um sinal que
   * dispara sempre deixa de ser sinal. O projeto já cometeu esse erro uma vez, quando
   * {@code ScreeningCoverageRiskRule} passou a exigir cobertura de {@code ADVERSE_MEDIA}
   * incondicionalmente e pontuou 100% das avaliações.
   */
  POLICY_AUTHORSHIP_UNKNOWN;

  /**
   * Se esta lacuna impede afirmar o que o motor decidiria. Falso só para a lacuna de autoria: ela
   * incompleta o dossiê da política, não a reconstrução da decisão.
   */
  public boolean affectsDecision() {
    return this != POLICY_AUTHORSHIP_UNKNOWN;
  }
}
