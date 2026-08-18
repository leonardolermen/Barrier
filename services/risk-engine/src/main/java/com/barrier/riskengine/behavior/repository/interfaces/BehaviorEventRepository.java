package com.barrier.riskengine.behavior.repository.interfaces;

import com.barrier.riskengine.behavior.domain.BehaviorEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acervo de fatos comportamentais. Append-only: não há {@code update} nem {@code delete} na
 * interface, e isso é a defesa — o tipo não oferece a operação que corromperia a trilha.
 */
public interface BehaviorEventRepository {

  /** @return vazio quando o {@code sourceEventId} já existe para o tenant (ingestão duplicada) */
  Optional<BehaviorEvent> append(BehaviorEvent event);

  /** Eventos do cliente numa janela, mais recentes primeiro. Base de toda regra comportamental. */
  List<BehaviorEvent> findRecent(UUID subjectId, String tenantId, Instant since, int limit);

  /** Quantos eventos daquele tipo o cliente teve na janela. */
  long countByTypeSince(UUID subjectId, String tenantId, String eventType, Instant since);
}
