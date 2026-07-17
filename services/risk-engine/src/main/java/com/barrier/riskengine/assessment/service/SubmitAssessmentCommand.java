package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.DocumentType;

/** Comando de entrada para submeter uma nova avaliação, no escopo de um tenant. */
public record SubmitAssessmentCommand(
    String tenantId,
    DocumentType documentType,
    String document,
    String name,
    String ip,
    String deviceId) {}
