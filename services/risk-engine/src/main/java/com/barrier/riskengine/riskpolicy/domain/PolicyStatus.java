package com.barrier.riskengine.riskpolicy.domain;

/**
 * Ciclo de vida de uma versão de {@link RiskPolicy}.
 *
 * <p>{@code DRAFT} -&gt; {@code ACTIVE} -&gt; {@code ARCHIVED}. Versão ativada é imutável: editar
 * gera versão nova, nunca reescreve a ativa. {@code SHADOW} entra em P2 (shadow mode e
 * backtesting) — não existe ainda nesta entrega.
 */
public enum PolicyStatus {
  DRAFT,
  ACTIVE,
  ARCHIVED
}
