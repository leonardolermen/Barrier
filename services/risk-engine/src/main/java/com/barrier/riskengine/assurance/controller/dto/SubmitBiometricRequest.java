package com.barrier.riskengine.assurance.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Submissão de biometria facial com prova de vida. Não carrega selfie/vídeo — só as referências
 * dos uploads feitos direto para o provedor (ADR-0016).
 *
 * @param selfieReference identificador do upload da selfie/vídeo
 * @param documentFaceReference referência da face extraída do documento na documentoscopia
 * @param submittedHash SHA-256 calculado no cliente sobre o que foi enviado
 * @param consent prova de consentimento para esta verificação; {@code null} é recusado com 400
 *     pelo service, e um objeto presente mas incompleto é recusado por Bean Validation ({@code
 *     @Valid} em cascata) antes disso
 */
public record SubmitBiometricRequest(
    @NotBlank String selfieReference,
    @NotBlank String documentFaceReference,
    @NotBlank String submittedHash,
    @Valid ConsentRequest consent) {}
