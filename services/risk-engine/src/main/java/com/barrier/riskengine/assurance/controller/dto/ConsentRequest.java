package com.barrier.riskengine.assurance.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Consentimento do titular para a verificação, do jeito que o parceiro submete.
 *
 * <p>Campo separado (não embutido nos demais) de propósito: ausência do objeto inteiro no JSON
 * vira {@code null} aqui, e é esse {@code null} que {@code AssuranceService} recusa com 400 — o
 * mesmo caminho de erro de um cliente que simplesmente esqueceu de mandar o consentimento.
 *
 * <p>Quando o objeto <b>existe</b> mas está incompleto (ex.: {@code reference} em branco), quem
 * recusa é a validação Bean Validation abaixo antes de chegar ao service — {@code
 * AssuranceConsent.validate()} é a segunda linha de defesa (chamadores internos que não passam
 * pelo controller), não a única.
 *
 * @param reference identificador do registro de consentimento (ex.: id da tela de aceite) — sem
 *     ele não há prova do aceite perante a LGPD
 * @param purpose finalidade declarada ao titular no momento da coleta
 * @param grantedAt quando o titular consentiu
 */
public record ConsentRequest(
    @NotBlank String reference, @NotBlank String purpose, @NotNull Instant grantedAt) {}
