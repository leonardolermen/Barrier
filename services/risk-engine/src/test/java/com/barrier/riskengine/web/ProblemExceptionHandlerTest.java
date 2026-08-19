package com.barrier.riskengine.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Nenhum handler pode devolver ao chamador a mensagem de uma exceção <b>não modelada</b>.
 *
 * <p><b>O vazamento concreto.</b> Havia um {@code @ExceptionHandler(IllegalStateException.class)}
 * mapeando para 409 com {@code e.getMessage()}. Como {@code IllegalStateException} é a exceção que
 * qualquer código lança para "estado inesperado", ela cobria tanto conflitos de negócio legítimos
 * quanto erros internos — e devolvia o texto dos dois. Foi assim que um chamador anônimo recebeu
 * <i>"Rota declara AuthenticatedTenant mas não está coberta pelo TenantAuthenticationFilter"</i>:
 * detalhe de arquitetura interna, com 409, para quem não estava autenticado.
 *
 * <p>Os mesmos handlers cobriam ainda {@code "SHA-256 indisponível na JVM"} e
 * {@code "JWKS do Serpro com chave RSA ilegível"} — mensagens que descrevem a implementação, não
 * um problema que o parceiro possa resolver.
 *
 * <p><b>A correção segue o padrão que o projeto já usa certo</b> em
 * {@code DocumentGateNotSatisfiedException}: conflito de negócio ganha exceção própria, e o
 * genérico deixa de ser tratado — vira 500 sem detalhe, que é o correto para erro de programação.
 * O tipo da exceção passa a ser a decisão de o que é público.
 */
class ProblemExceptionHandlerTest {

  /**
   * Exceções da plataforma que nunca devem ter tratamento dedicado: são "algo saiu do esperado", e
   * mapear qualquer uma delas devolve mensagem interna a quem chamou.
   */
  private static final List<Class<? extends Throwable>> GENERICAS =
      List.of(
          IllegalStateException.class,
          RuntimeException.class,
          Exception.class,
          Throwable.class,
          NullPointerException.class,
          UnsupportedOperationException.class);

  @Test
  void nenhumHandlerTrataExcecaoGenericaDaPlataforma() {
    for (var method : ProblemExceptionHandler.class.getDeclaredMethods()) {
      ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
      if (annotation == null) {
        continue;
      }
      assertThat(Arrays.asList(annotation.value()))
          .as(
              "%s trata exceção genérica: a mensagem de qualquer erro interno vira resposta da API",
              method.getName())
          .doesNotContainAnyElementsOf(GENERICAS);
    }
  }

  /**
   * {@code IllegalArgumentException} é a exceção da plataforma que o projeto usa deliberadamente
   * para entrada inválida (documento malformado, parâmetro fora do range no
   * {@code TenantRiskConfigValidator}), e o 400 com a mensagem é o comportamento desejado — o
   * chamador precisa saber <b>o que</b> na requisição dele está errado.
   *
   * <p>Fica registrado aqui como exceção consciente à regra acima, para não ser "corrigida" depois
   * por simetria.
   */
  @Test
  void entradaInvalidaContinuaExplicandoOMotivoAoChamador() {
    ProblemDetail problem =
        new ProblemExceptionHandler()
            .handleBadRequest(new IllegalArgumentException("documento inválido"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getDetail()).isEqualTo("documento inválido");
  }
}
