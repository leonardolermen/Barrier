package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.DocumentType;

/** Comando de entrada para submeter uma nova avaliação. */
public record SubmitAssessmentCommand(DocumentType documentType, String document, String name) {}
