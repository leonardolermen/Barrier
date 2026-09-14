package com.barrier.riskengine.riskpolicy.controller.dto;

import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Cria uma versão {@code DRAFT}. {@code createdBy} segue texto informado pelo chamador -- mesmo
 * raciocínio de {@code reviewedBy} em {@code ReviewDecisionRequest}: a credencial identifica o
 * sistema cliente, não a pessoa.
 */
public record CreatePolicyRequest(
    @NotNull PolicyDomain domain,
    @NotEmpty @Valid List<PolicyRuleDto> rules,
    @NotBlank String createdBy) {}
