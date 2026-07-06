package com.barrier.riskengine.assessment.domain;

import java.util.Objects;
import java.util.UUID;

/** Identificador único de uma avaliação; também usado como id de correlação. */
public record AssessmentId(UUID value) {

  public AssessmentId {
    Objects.requireNonNull(value, "value");
  }

  public static AssessmentId newId() {
    return new AssessmentId(UUID.randomUUID());
  }

  public static AssessmentId of(String value) {
    try {
      return new AssessmentId(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("AssessmentId inválido: " + value, e);
    }
  }

  public String asString() {
    return value.toString();
  }
}
