package com.barrier.commons.outbox;

/** Estado de um evento na tabela de outbox. */
public enum OutboxStatus {
  PENDING,
  SENT
}
