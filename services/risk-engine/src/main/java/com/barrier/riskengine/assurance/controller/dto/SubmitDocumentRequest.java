package com.barrier.riskengine.assurance.controller.dto;

/**
 * Submissão de documentoscopia. Não carrega imagem — só a referência do upload feito direto do
 * dispositivo para o provedor (ADR-0016, ver {@code DocumentSubmission}).
 *
 * @param captureReference identificador do upload no provedor
 * @param documentType tipo declarado (RG, CNH, PASSAPORTE)
 * @param submittedHash SHA-256 calculado no cliente sobre a imagem enviada
 * @param consent prova de consentimento para esta verificação; {@code null} é recusado com 400
 */
public record SubmitDocumentRequest(
    String captureReference, String documentType, String submittedHash, ConsentRequest consent) {}
