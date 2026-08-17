package com.barrier.riskengine.rescreening.policy.domain;

/**
 * Por que se está cogitando reavaliar um cliente. Cada gatilho declara dois comportamentos — se
 * exige alteração material e se respeita o intervalo mínimo (ADR-0019).
 *
 * <p><b>O bypass é propriedade do enum, e isso é a defesa.</b> A regra que mais fácil se erra ao
 * importar esta política do ecossistema Origem é aplicar intervalo mínimo ao rescreening por
 * watchlist "para economizar consulta": entrada nova em lista de sanção é fato adverso novo, e
 * suprimi-la porque o cliente foi reavaliado há 30 dias descumpre a Circular 3.978. Deixar isso
 * como convenção de código sobreviveria até o primeiro refactor; como valor do enum, com teste
 * dedicado, quebra o build.
 */
public enum ReassessmentTrigger {

  /** Lista de sanção/PEP passou a apontar o cliente. Fato adverso novo — nunca é suprimido. */
  WATCHLIST_DELTA(false, false),

  /** Documentoscopia ou biometria registrada. Evento sobre a identidade, não rotina. */
  ASSURANCE(false, false),

  /** Pedido explícito de operador. Quem pediu sabe por quê. */
  MANUAL(false, false),

  /**
   * Cadastro alterado — só reavalia se a alteração mexe em algo que a decisão usa
   * ({@code MaterialProfileChange}), e aí reavalia mesmo: <b>fura o intervalo mínimo</b>.
   *
   * <p>Decisão de produto (2026-08-15). Com o intervalo valendo, cliente LOW só seria reavaliado
   * depois de 1095 dias, e a checagem de materialidade viraria decorativa — o comportamento
   * efetivo seria o de antes da política (nunca reavaliar por cadastro). Com o bypass, o freio
   * passa a ser inteiramente a materialidade: por isso ela compara valor a valor em vez de
   * confiar em "houve um PUT".
   */
  PROFILE_PATCH(true, false),

  /** Parceiro submeteu o mesmo cliente de novo. */
  REINTAKE(true, true),

  /** Reavaliação de rotina, sem fato novo. É a que o intervalo mínimo existe para governar. */
  PERIODIC(false, true);

  private final boolean requiresMaterialChange;
  private final boolean respectsMinimumInterval;

  ReassessmentTrigger(boolean requiresMaterialChange, boolean respectsMinimumInterval) {
    this.requiresMaterialChange = requiresMaterialChange;
    this.respectsMinimumInterval = respectsMinimumInterval;
  }

  public boolean requiresMaterialChange() {
    return requiresMaterialChange;
  }

  public boolean respectsMinimumInterval() {
    return respectsMinimumInterval;
  }
}
