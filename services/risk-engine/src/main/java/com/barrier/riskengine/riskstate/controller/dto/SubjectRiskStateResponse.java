package com.barrier.riskengine.riskstate.controller.dto;

import java.time.Instant;

/**
 * Risco corrente do cliente. O documento sai sempre mascarado, e {@code assessmentId} aponta a
 * avaliação que produziu este estado — é por ela que o parceiro chega à trilha completa.
 *
 * @param fromProjection falso quando a resposta veio do fallback pela última avaliação concluída,
 *     e não da projeção. O parceiro não precisa distinguir para usar o dado, mas a diferença
 *     importa numa contestação: a projeção é mantida por escrita, o fallback é uma leitura
 *     derivada.
 */
public record SubjectRiskStateResponse(
    String subjectId,
    String documentType,
    String document,
    String riskLevel,
    // Nulo no fallback, de propósito: a última avaliação concluída dá o nível e a decisão, mas o
    // score numérico vive em `risk_scores`. Devolver 0 seria mentir — 0 é um score válido, e o
    // mais favorável que existe.
    Integer riskScore,
    String decision,
    String assessmentId,
    String engineVersion,
    Instant evaluatedAt,
    boolean fromProjection) {}
