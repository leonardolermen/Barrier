package com.barrier.webhook.repository;

import com.barrier.webhook.domain.Delivery;
import com.barrier.webhook.domain.DeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

@Repository
class DeliveryRepositoryImpl implements DeliveryRepository {

  private final DeliveryJpaRepository jpa;

  DeliveryRepositoryImpl(DeliveryJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Delivery save(Delivery delivery) {
    return DeliveryEntityMapper.toDomain(jpa.save(DeliveryEntityMapper.toEntity(delivery)));
  }

  @Override
  public boolean existsByEventId(UUID eventId) {
    return jpa.existsByEventId(eventId);
  }

  /**
   * A posse é gravada por dirty checking: as entidades vêm gerenciadas da consulta com lock, e o
   * {@code claimed_at} é persistido no commit da transação que envolve esta chamada — que é curta e
   * <b>não</b> contém o POST para o cliente.
   */
  @Override
  public List<Delivery> claimDue(Instant now, int limit, Duration lease) {
    List<DeliveryEntity> claimable =
        jpa.selectClaimable(
            List.of(DeliveryStatus.PENDING, DeliveryStatus.FAILED),
            now,
            now.minus(lease),
            Limit.of(limit));
    // SEGUNDA trava, e ela é indispensável: a query exclui chaves JÁ em voo, mas duas entregas
    // recém-criadas do mesmo subject ainda não têm claimedAt — nenhuma bloqueia a outra, e as duas
    // seriam elegíveis no mesmo lote. Sem esta linha a ordem quebraria dentro de um único ciclo,
    // que é justamente o caso mais comum: os dois eventos do mesmo cliente chegam juntos.
    Set<String> chavesNoLote = new HashSet<>();
    List<DeliveryEntity> lote =
        claimable.stream()
            .filter(e -> e.getPartitionKey() == null || chavesNoLote.add(e.getPartitionKey()))
            .toList();

    lote.forEach(entity -> entity.setClaimedAt(now));
    return lote.stream().map(DeliveryEntityMapper::toDomain).toList();
  }
}
