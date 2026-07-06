package com.barrier.commons.outbox;

import com.barrier.commons.event.EventEnvelope;

/** Abstração de publicação de eventos (implementada por um adapter de mensageria). */
public interface EventPublisher {

  /** Publica o envelope de forma síncrona; lança exceção se a publicação falhar. */
  void publish(EventEnvelope envelope);
}
