package com.barrier.riskengine.identity.service;

/**
 * Comando de verificação de identidade. Usa apenas tipos primitivos para manter o módulo
 * identity independente do módulo assessment (sem ciclo entre contextos).
 */
public record VerifyIdentityCommand(
    String assessmentId, String documentType, String documentDigits, String name) {}
