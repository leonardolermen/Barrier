package com.barrier.riskengine.assurance.client;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;

/**
 * Par desfecho + dado extraído da documentoscopia — mesmo padrão de {@code IdentityResult} para
 * o bureau: o desfecho é o que sustenta decisão automática, o dado extraído é insumo para quem
 * verificar o cadastro depois.
 *
 * @param extracted campos lidos do documento; {@code null} quando o desfecho não é {@code PASS}
 *     — documento reprovado ou inconclusivo não produz dado confiável para comparar com nada
 */
public record DocumentVerificationResult(
    AssuranceCheck check, ExtractedDocumentFields extracted) {}
