package com.barrier.riskengine.riskpolicy.service;

/**
 * Uma {@link com.barrier.riskengine.riskpolicy.domain.PolicyRule} viola uma das travas do piso
 * regulatório e não compila — ver {@link PolicyCompiler}.
 *
 * <p>A mensagem já vem formatada de quem a lança: cita o código da regra e, quando aplicável, o
 * campo e o operador envolvidos, além de nomear a trava violada. O {@code
 * ProblemExceptionHandler} mapeia esta exceção para 400 devolvendo a mensagem como está —
 * mensagem que não diz o que corrigir empurra o dev do parceiro para o suporte, o mesmo defeito
 * já corrigido uma vez na superfície pública do OpenAPI ({@code AuthenticatedTenant} publicado
 * como parâmetro inexistente).
 */
public class PolicyCompilationException extends RuntimeException {

  public PolicyCompilationException(String message) {
    super(message);
  }
}
