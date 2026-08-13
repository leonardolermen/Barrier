package com.barrier.riskengine.assurance.controller.dto;

/**
 * Submissão de biometria facial com prova de vida. Não carrega selfie/vídeo — só as referências
 * dos uploads feitos direto para o provedor (ADR-0016).
 *
 * @param selfieReference identificador do upload da selfie/vídeo
 * @param documentFaceReference referência da face extraída do documento na documentoscopia
 * @param submittedHash SHA-256 calculado no cliente sobre o que foi enviado
 * @param consent prova de consentimento para esta verificação; {@code null} é recusado com 400
 */
public record SubmitBiometricRequest(
    String selfieReference,
    String documentFaceReference,
    String submittedHash,
    ConsentRequest consent) {}
