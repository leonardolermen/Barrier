package com.barrier.riskengine.assurance.domain;

/** O que foi verificado. */
public enum AssuranceKind {

  /** Documentoscopia: o documento apresentado é autêntico e não foi adulterado. */
  DOCUMENT,

  /** Biometria facial com prova de vida: quem apresentou é a pessoa do documento, e está viva. */
  BIOMETRIC
}
