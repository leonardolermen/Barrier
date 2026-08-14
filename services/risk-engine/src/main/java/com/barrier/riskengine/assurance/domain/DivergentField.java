package com.barrier.riskengine.assurance.domain;

/**
 * Campo que a documentoscopia leu do documento e que diverge do que o titular declarou.
 *
 * <p>Deliberadamente sem {@code DOCUMENT}: o número que a documentoscopia lê da imagem é o
 * número do documento apresentado (RG, CNH...), não necessariamente o CPF/CNPJ que identifica o
 * {@code Subject} (ADR-0011) — comparar os dois é comparar grandezas diferentes e gera
 * divergência sistemática (todo RG "diverge" do CPF do cadastro). Sem provedor que devolva o
 * CPF/CNPJ extraído do documento, essa comparação não tem como ser feita corretamente.
 */
public enum DivergentField {

  /** Nome lido do documento diverge do nome declarado no {@code Subject}. */
  NAME,

  /** Nascimento lido do documento diverge do declarado no cadastro (CMN 4.753). */
  BIRTH_DATE
}
