package com.barrier.riskengine.identity.client;

/**
 * Sinaliza que o bureau está indisponível (timeout, erro de rede, 5xx). Não é uma reprovação
 * de identidade: a avaliação registra o resultado como indisponível e prossegue.
 */
public class BureauUnavailableException extends RuntimeException {

  public BureauUnavailableException(String message) {
    super(message);
  }

  public BureauUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
