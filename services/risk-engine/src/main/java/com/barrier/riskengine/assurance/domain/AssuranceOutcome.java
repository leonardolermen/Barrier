package com.barrier.riskengine.assurance.domain;

/**
 * Desfecho de uma verificação de documentoscopia ou biometria.
 *
 * <p>{@link #INCONCLUSIVE} e {@link #UNAVAILABLE} são estados distintos de propósito, pelo mesmo
 * motivo que a cadeia de bureaus separa "não encontrado" de "indisponível": foto ruim exige nova
 * tentativa do cliente, provedor fora do ar exige nada dele — e tratar os dois como reprovação
 * recusaria clientes legítimos por falha nossa.
 */
public enum AssuranceOutcome {

  /** Documento autêntico / face confere com prova de vida. */
  PASS,

  /** Adulteração detectada, ou face não confere, ou prova de vida falhou. */
  FAIL,

  /** Qualidade insuficiente para decidir (foto tremida, reflexo, corte). */
  INCONCLUSIVE,

  /** Provedor indisponível — nada foi verificado, e isso não é culpa do cliente. */
  UNAVAILABLE,

  /**
   * Verificação iniciada, sem desfecho ainda — fluxos assíncronos (ex.: biometria por PIN do
   * Datavalid/Serpro, onde o cidadão captura a selfie depois, no app gov.br). Não é um quinto
   * desfecho no sentido de {@link #PASS}/{@link #FAIL}/{@link #INCONCLUSIVE}/{@link
   * #UNAVAILABLE}: é a ausência de desfecho, só que persistida porque a chamada que a criou já
   * aconteceu (o PIN foi emitido, a chamada paga foi feita). {@code IdentityAssuranceRiskRule} e o
   * gatilho de reavaliação tratam {@code PENDING} como "ainda não há verificação" — não pontua, não
   * dispara reavaliação, fail-closed: a avaliação permanece em {@code SOLICITAR_DOCUMENTO} até um
   * poller trazer o desfecho final e substituir este registro por um novo.
   */
  PENDING
}
