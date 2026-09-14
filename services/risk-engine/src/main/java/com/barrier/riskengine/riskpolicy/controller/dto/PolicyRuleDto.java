package com.barrier.riskengine.riskpolicy.controller.dto;

import com.barrier.riskengine.risk.domain.enums.RiskRecommendation;
import com.barrier.riskengine.risk.domain.enums.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Uma regra de política, de e para o parceiro. Usada tanto na requisição de criação quanto na
 * resposta -- a forma é simétrica, então um único tipo evita duas cópias divergentes.
 *
 * <p>{@code score} não carrega {@code @Min(0)}: score negativo é a trava 1 do {@code
 * PolicyCompiler} (o único jeito de uma regra custom afrouxar a decisão do motor), e a mensagem
 * que o compilador produz já cita o código da regra e o valor -- uma validação de bean aqui
 * devolveria um 400 genérico e esconderia essa mensagem mais informativa.
 */
public record PolicyRuleDto(
    @NotBlank String code,
    @NotBlank String name,
    @NotNull @Valid PolicyConditionDto when,
    int score,
    @NotNull Severity severity,
    RiskRecommendation recommendation) {}
