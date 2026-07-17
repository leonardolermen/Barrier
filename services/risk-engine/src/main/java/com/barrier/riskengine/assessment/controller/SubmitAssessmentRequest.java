package com.barrier.riskengine.assessment.controller;

import com.barrier.riskengine.assessment.domain.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Corpo da requisição de submissão de avaliação. {@code ip}/{@code deviceId} são opcionais —
 * sinais de rede (GeoIP, reuso de device) para o motor de risco; cliente que não os envia
 * simplesmente não alimenta essas regras.
 */
public record SubmitAssessmentRequest(
    @NotNull DocumentType documentType,
    @NotBlank String document,
    @NotBlank String name,
    String ip,
    String deviceId) {}
