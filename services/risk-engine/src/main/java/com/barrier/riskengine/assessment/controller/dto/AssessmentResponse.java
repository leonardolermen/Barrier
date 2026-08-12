package com.barrier.riskengine.assessment.controller.dto;

import java.time.Instant;
import java.util.List;

/** Representação externa de uma avaliação (POST 202 e GET). */
public record AssessmentResponse(
    String id,
    String status,
    String riskLevel,
    String decision,
    List<String> factors,
    Instant createdAt,
    Instant completedAt,
    String reviewedBy,
    String reviewedByKey,
    String reviewReason,
    Instant reviewedAt) {}
