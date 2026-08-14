package com.barrier.riskengine.assurance.controller.dto;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import java.time.Instant;

/**
 * O check gravado, para o parceiro que submeteu a verificação.
 *
 * <p><b>Não tem campo de dado extraído do documento</b> (nome, número, nascimento lidos da
 * imagem) — de propósito. Devolvê-los transformaria este endpoint num serviço de OCR sobre
 * documento de terceiro; o parceiro recebe o desfecho da verificação, não o conteúdo dela.
 *
 * <p><b>Não tem id de reavaliação.</b> A reavaliação é disparada por {@code
 * AssuranceRecordedListener.onRecorded}, registrado para rodar em {@code afterCommit} da
 * transação deste request e numa transação própria ({@code REQUIRES_NEW}) — ou seja, depois que
 * este método já devolveu a resposta ao chamador. Não há id para devolver nesse ponto sem
 * antecipar a chamada ao listener para dentro da transação da submissão, o que quebraria a
 * garantia de isolamento por listener que {@code AssuranceService.notifyListeners} documenta.
 * Preferível a resposta honesta ao campo que às vezes mentiria.
 *
 * @param id identificador do check
 * @param kind documentoscopia ou biometria
 * @param outcome desfecho da verificação
 * @param score confiança 0..100 quando o provedor a fornece; {@code null} quando só há desfecho
 * @param provider provedor que verificou
 * @param providerReference referência da consulta no provedor
 * @param algorithmVersion versão do modelo do provedor
 * @param checkedAt quando a verificação foi feita
 */
public record AssuranceCheckResponse(
    String id,
    String kind,
    String outcome,
    Integer score,
    String provider,
    String providerReference,
    String algorithmVersion,
    Instant checkedAt) {

  public static AssuranceCheckResponse of(AssuranceCheck check) {
    return new AssuranceCheckResponse(
        check.id().toString(),
        check.kind().name(),
        check.outcome().name(),
        check.score(),
        check.provider(),
        check.providerReference(),
        check.algorithmVersion(),
        check.checkedAt());
  }
}
