package com.barrier.riskengine.riskpolicy.domain;

/**
 * Domínio de decisão a que uma política se aplica.
 *
 * <p>Existe com um único valor de propósito: é o que faz o domínio transacional (P3) entrar como
 * valor novo em vez de migração de conceito.
 */
public enum PolicyDomain {
  ONBOARDING
}
