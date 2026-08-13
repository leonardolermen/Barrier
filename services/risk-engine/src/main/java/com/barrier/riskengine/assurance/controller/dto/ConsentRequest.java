package com.barrier.riskengine.assurance.controller.dto;

import java.time.Instant;

/**
 * Consentimento do titular para a verificação, do jeito que o parceiro submete.
 *
 * <p>Campo separado (não embutido nos demais) de propósito: ausência dele no JSON vira {@code
 * null} aqui, e é esse {@code null} que {@code AssuranceService} recusa com 400 — o mesmo
 * caminho de erro de um cliente que simplesmente esqueceu de mandar o consentimento.
 *
 * @param reference identificador do registro de consentimento (ex.: id da tela de aceite)
 * @param purpose finalidade declarada ao titular no momento da coleta
 * @param grantedAt quando o titular consentiu
 */
public record ConsentRequest(String reference, String purpose, Instant grantedAt) {}
