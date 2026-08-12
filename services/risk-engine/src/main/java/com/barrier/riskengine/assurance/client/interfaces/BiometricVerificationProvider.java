package com.barrier.riskengine.assurance.client.interfaces;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.client.BiometricSubmission;
import java.util.UUID;

/**
 * Biometria facial com prova de vida: a face apresentada é a do documento, e é de uma pessoa
 * presente — não de uma foto de foto, máscara ou vídeo.
 *
 * <p>Prova de vida e comparação andam juntas de propósito: comparar face sem prova de vida aprova
 * quem tem a foto do titular, que é o ataque mais barato que existe.
 */
public interface BiometricVerificationProvider {

  AssuranceCheck verify(UUID subjectId, String tenantId, BiometricSubmission submission);

  String name();
}
