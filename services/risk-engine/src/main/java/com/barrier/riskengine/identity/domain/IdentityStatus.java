package com.barrier.riskengine.identity.domain;

/** Resultado da verificação de identidade. */
public enum IdentityStatus {
  /** Documento confere com o bureau. */
  VERIFIED,
  /** Documento não encontrado no bureau. */
  NOT_FOUND,
  /** Documento encontrado, mas dados divergem (ex.: nome). */
  MISMATCH,
  /** Documento encontrado, titular falecido — indício de fraude, não divergência cadastral. */
  DECEASED,
  /** Bureau indisponível; verificação não pôde ser concluída. */
  UNAVAILABLE
}
