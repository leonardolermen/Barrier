package com.barrier.riskengine.assurance.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Submissão de biometria facial com prova de vida. Não carrega selfie/vídeo — só as referências
 * dos uploads feitos direto para o provedor (ADR-0016).
 *
 * <p>{@code @Size} pelo mesmo motivo de {@code SubmitDocumentRequest}: sem limite, entrada maior
 * que a coluna (V035) vira 500 em vez de 400. {@code selfieReference} tem limite de 115, não
 * 120, pelo mesmo motivo de {@code captureReference} lá: {@code StubBiometricVerificationProvider}
 * grava {@code "stub:" + selfieReference} em {@code provider_reference VARCHAR(120)}, e os 5
 * caracteres do prefixo têm de caber dentro do limite da coluna.
 *
 * @param selfieReference identificador do upload da selfie/vídeo
 * @param documentFaceReference referência da face extraída do documento na documentoscopia
 * @param submittedHash SHA-256 calculado no cliente sobre o que foi enviado
 * @param consent prova de consentimento para esta verificação; {@code null} é recusado com 400
 *     pelo service, e um objeto presente mas incompleto é recusado por Bean Validation ({@code
 *     @Valid} em cascata) antes disso
 */
public record SubmitBiometricRequest(
    @NotBlank @Size(max = 115) String selfieReference,
    @NotBlank @Size(max = 120) String documentFaceReference,
    @NotBlank @Size(max = 64) String submittedHash,
    @Valid ConsentRequest consent) {}
