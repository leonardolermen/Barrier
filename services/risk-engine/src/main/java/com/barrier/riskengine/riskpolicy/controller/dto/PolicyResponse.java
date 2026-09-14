package com.barrier.riskengine.riskpolicy.controller.dto;

import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import java.time.Instant;
import java.util.List;

/**
 * Representação externa de uma versão de {@code RiskPolicy} (POST 201, GET, activate, archive).
 */
public record PolicyResponse(
    int version,
    PolicyDomain domain,
    String status,
    int catalogVersion,
    List<PolicyRuleDto> rules,
    String createdBy,
    Instant createdAt,
    String activatedBy,
    Instant activatedAt,
    Instant archivedAt) {}
