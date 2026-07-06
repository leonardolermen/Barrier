package com.barrier.commons.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso à tabela de outbox. */
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

  /** Eventos pendentes, mais antigos primeiro, limitados para processamento em lote. */
  List<OutboxEvent> findByStatusOrderByOccurredAtAsc(OutboxStatus status, Limit limit);
}
