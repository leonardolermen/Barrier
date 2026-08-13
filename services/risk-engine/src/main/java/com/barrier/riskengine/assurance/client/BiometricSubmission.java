package com.barrier.riskengine.assurance.client;

/**
 * O que o parceiro submete para biometria.
 *
 * @param selfieReference identificador do upload da selfie/vídeo direto para o provedor
 * @param documentFaceReference referência da face extraída do documento na documentoscopia; é
 *     contra ela que a comparação é feita
 * @param submittedHash SHA-256 calculado no cliente sobre o que foi enviado
 */
public record BiometricSubmission(
    String selfieReference, String documentFaceReference, String submittedHash) {}
