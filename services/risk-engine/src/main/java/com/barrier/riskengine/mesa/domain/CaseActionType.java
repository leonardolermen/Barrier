package com.barrier.riskengine.mesa.domain;

/**
 * O que um humano fez com o caso.
 *
 * <p>Ações são registradas como <b>eventos</b>, não só o desfecho final. Guardar apenas a decisão
 * destruiria a informação de que o SLA depende: o desfecho não diz quanto tempo o caso passou
 * esperando alguém de fora da mesa.
 */
public enum CaseActionType {

  /** Analista assumiu o caso. */
  ASSIGNED,

  /** Caso mudou de fila. O detalhe registra origem e destino. */
  MOVED,

  /** Pediu documento ao parceiro — abre uma janela de espera candidata a pausa de SLA. */
  DOCUMENT_REQUESTED,

  /** Documento recebido — fecha a janela. Sem este par, a espera não é provável. */
  DOCUMENT_RECEIVED,

  /** Observação livre do analista. Não afeta SLA. */
  NOTE,

  /** Decisão humana registrada (APPROVE/REJECT). Encerra o caso. */
  DECIDED
}
