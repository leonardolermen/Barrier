package com.barrier.riskengine.assurance.client;

/**
 * O que o parceiro submete para documentoscopia.
 *
 * @param captureReference identificador do upload feito direto do dispositivo para o provedor — é
 *     por isso que não há {@code byte[]} aqui
 * @param documentType tipo declarado (RG, CNH, PASSAPORTE); a divergência com o que a
 *     documentoscopia lê é sinal, não detalhe
 * @param submittedHash SHA-256 calculado no cliente sobre a imagem enviada, para a prova futura
 */
public record DocumentSubmission(
    String captureReference, String documentType, String submittedHash) {}
