package com.barrier.webhook.controller;

/**
 * Mensagem que <b>nunca</b> vai ser processada com sucesso: JSON inválido, envelope sem os campos
 * obrigatórios, payload que não é um objeto.
 *
 * <p>Existe para separar as duas falhas que antes eram tratadas igual. Falha transitória (banco
 * fora do ar) tem que ser retentada e não pode commitar o offset; mensagem malformada, retentada,
 * bloqueia a partição para sempre. Esta vai direto para a DLT.
 */
public class MalformedEventException extends RuntimeException {

  public MalformedEventException(String message, Throwable cause) {
    super(message, cause);
  }
}
