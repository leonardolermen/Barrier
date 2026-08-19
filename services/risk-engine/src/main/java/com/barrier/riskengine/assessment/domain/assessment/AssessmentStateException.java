package com.barrier.riskengine.assessment.domain.assessment;

/**
 * Transição de estado inválida no ciclo de vida de uma avaliação — decidir uma que já concluiu,
 * decidir uma que não está em revisão, registrar falha em uma que não está em análise.
 *
 * <p><b>Por que não {@code IllegalStateException}.</b> Estes são conflitos de <b>negócio</b>: o
 * chamador precisa saber o que aconteceu (409 com o motivo é a resposta certa — dois revisores
 * decidindo o mesmo caso é um cenário real). Enquanto eram {@code IllegalStateException}, o
 * handler precisava mapear a exceção genérica da plataforma para 409 com a mensagem — e assim
 * passava a devolver também o texto de qualquer erro interno que a usasse, como
 * {@code "SHA-256 indisponível na JVM"} ou o detalhe de configuração do filtro de autenticação.
 *
 * <p>O tipo é o que separa "conflito que o parceiro deve ler" de "erro de programação que ele não
 * deve ver". Mesmo padrão de {@code DocumentGateNotSatisfiedException}.
 */
public class AssessmentStateException extends RuntimeException {

  public AssessmentStateException(String message) {
    super(message);
  }
}
