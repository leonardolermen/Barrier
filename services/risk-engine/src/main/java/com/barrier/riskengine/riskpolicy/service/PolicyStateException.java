package com.barrier.riskengine.riskpolicy.service;

/**
 * Transição de estado inválida no ciclo de vida de uma {@link
 * com.barrier.riskengine.riskpolicy.domain.RiskPolicy} — hoje, duas: "ativar uma versão que não
 * está em DRAFT" e "arquivar uma versão que já está ARCHIVED".
 *
 * <p>Não {@code IllegalStateException}, mesmo raciocínio de {@code AssessmentStateException}: é
 * um conflito de negócio que o parceiro precisa ler (409, mapeado pelo {@code
 * ProblemExceptionHandler}), não um erro de programação a esconder atrás de 500.
 */
public class PolicyStateException extends RuntimeException {

  public PolicyStateException(String message) {
    super(message);
  }
}
