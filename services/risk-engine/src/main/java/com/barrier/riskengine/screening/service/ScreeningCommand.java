package com.barrier.riskengine.screening.service;

/**
 * Comando de screening. Usa apenas primitivos para manter o módulo screening independente do
 * módulo assessment (sem ciclo entre contextos).
 */
public record ScreeningCommand(
    String assessmentId, String documentType, String documentDigits, String name) {}
