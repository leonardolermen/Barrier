package com.barrier.riskengine.subject.profile.domain;

/**
 * Campo cadastral cuja veracidade é verificável por um meio independente do que o cliente
 * declarou.
 *
 * <p>Deliberadamente curto. Ocupação e renda declarada <b>não</b> estão aqui: não existe fonte
 * independente que as confirme, e criar um estado "verificado" que na prática só repete a
 * declaração é pior que assumir que são declaratórias — dá a um dado não verificado a aparência
 * de verificado.
 */
public enum VerifiableField {

  /** Confirmado por código enviado ao próprio número (OTP). */
  PHONE,

  /** Confirmado por código enviado ao próprio endereço de e-mail (OTP). */
  EMAIL,

  /** Conferido contra a data que o bureau devolve para o CPF. */
  BIRTH_DATE,

  /** Conferido contra base de endereçamento (CEP × logradouro × UF). */
  ADDRESS
}
