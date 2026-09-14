package com.barrier.riskengine.riskpolicy.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code activatedBy} segue texto informado pelo chamador, mesmo padrão de {@code createdBy}. */
public record ActivatePolicyRequest(@NotBlank String activatedBy) {}
