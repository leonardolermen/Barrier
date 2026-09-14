package com.barrier.riskengine.riskpolicy.domain.catalog;

/** Tipo de um campo do catálogo. Decide quais operadores são válidos sobre ele. */
public enum PolicyFieldType {
  DATE,
  NUMBER,
  STRING,
  ENUM,
  BOOLEAN,
  LIST
}
