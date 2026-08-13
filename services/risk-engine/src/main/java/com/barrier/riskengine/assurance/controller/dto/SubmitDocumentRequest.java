package com.barrier.riskengine.assurance.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Submissão de documentoscopia. Não carrega imagem — só a referência do upload feito direto do
 * dispositivo para o provedor (ADR-0016, ver {@code DocumentSubmission}).
 *
 * @param captureReference identificador do upload no provedor
 * @param documentType tipo declarado (RG, CNH, PASSAPORTE)
 * @param submittedHash SHA-256 calculado no cliente sobre a imagem enviada
 * @param consent prova de consentimento para esta verificação; {@code null} é recusado com 400
 *     pelo service, e um objeto presente mas incompleto é recusado por Bean Validation ({@code
 *     @Valid} em cascata) antes disso
 */
public record SubmitDocumentRequest(
    @NotBlank String captureReference,
    @NotBlank String documentType,
    @NotBlank String submittedHash,
    @Valid ConsentRequest consent) {}
