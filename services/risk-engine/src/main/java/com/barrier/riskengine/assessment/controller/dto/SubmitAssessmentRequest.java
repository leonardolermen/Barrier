package com.barrier.riskengine.assessment.controller.dto;

import com.barrier.riskengine.assessment.domain.documents.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Corpo da requisição de submissão de avaliação. */
public record SubmitAssessmentRequest(
    @NotNull DocumentType documentType, @NotBlank String document, @NotBlank String name) {}
