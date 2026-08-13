package com.barrier.riskengine.assurance.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Submissão de biometria facial com prova de vida. Não carrega selfie/vídeo — só as referências
 * dos uploads feitos direto para o provedor (ADR-0016).
 *
 * <p>{@code @Size} pelo mesmo motivo de {@code SubmitDocumentRequest}: sem limite, entrada maior
 * que a coluna (V035) vira 500 em vez de 400.
 *
 * @param selfieReference identificador do upload da selfie/vídeo
 * @param documentFaceReference referência da face extraída do documento na documentoscopia
 * @param submittedHash SHA-256 calculado no cliente sobre o que foi enviado
 * @param consent prova de consentimento para esta verificação; {@code null} é recusado com 400
 *     pelo service, e um objeto presente mas incompleto é recusado por Bean Validation ({@code
 *     @Valid} em cascata) antes disso
 */
public record SubmitBiometricRequest(
    @NotBlank @Size(max = 120) String selfieReference,
    @NotBlank @Size(max = 120) String documentFaceReference,
    @NotBlank @Size(max = 64) String submittedHash,
    @Valid ConsentRequest consent) {}
