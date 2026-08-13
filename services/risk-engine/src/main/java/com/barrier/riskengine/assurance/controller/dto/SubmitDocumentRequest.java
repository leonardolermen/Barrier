package com.barrier.riskengine.assurance.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Submissão de documentoscopia. Não carrega imagem — só a referência do upload feito direto do
 * dispositivo para o provedor (ADR-0016, ver {@code DocumentSubmission}).
 *
 * <p>{@code @Size} nos três campos de texto: sem limite, uma entrada maior que a coluna
 * ({@code identity_assurance_checks.provider_reference VARCHAR(120)} /
 * {@code .submitted_hash VARCHAR(64)}, migration V035) estoura {@code
 * DataIntegrityViolationException} sem handler — parceiro mandando hash de 200 caracteres recebia
 * 500 em vez de 400.
 *
 * <p>{@code captureReference} tem limite de 115, não 120: {@code StubDocumentVerificationProvider}
 * grava {@code "stub:" + captureReference} em {@code provider_reference VARCHAR(120)} — os 5
 * caracteres do prefixo têm de caber dentro do limite da coluna, senão uma referência de 116 a
 * 120 caracteres passa pela Bean Validation e só estoura na persistência (o mesmo 500 que este
 * {@code @Size} existe para evitar).
 *
 * @param captureReference identificador do upload no provedor
 * @param documentType tipo declarado (RG, CNH, PASSAPORTE)
 * @param submittedHash SHA-256 calculado no cliente sobre a imagem enviada
 * @param consent prova de consentimento para esta verificação; {@code null} é recusado com 400
 *     pelo service, e um objeto presente mas incompleto é recusado por Bean Validation ({@code
 *     @Valid} em cascata) antes disso
 */
public record SubmitDocumentRequest(
    @NotBlank @Size(max = 115) String captureReference,
    @NotBlank @Size(max = 30) String documentType,
    @NotBlank @Size(max = 64) String submittedHash,
    @Valid ConsentRequest consent) {}
