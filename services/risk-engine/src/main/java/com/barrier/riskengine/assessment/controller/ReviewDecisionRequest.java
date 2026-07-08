package com.barrier.riskengine.assessment.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Decisão humana de uma avaliação em revisão. */
public record ReviewDecisionRequest(
    @NotNull Decision decision, @NotBlank String reviewedBy, String reason) {

  public enum Decision {
    APPROVE,
    REJECT
  }
}
