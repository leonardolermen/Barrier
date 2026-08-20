package com.barrier.webhook.repository;

import com.barrier.webhook.domain.DeliveryStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.QueryHint;

interface DeliveryJpaRepository extends JpaRepository<DeliveryEntity, UUID> {

  boolean existsByEventId(UUID eventId);

  /**
   * Entregas vencidas cuja posse está livre ou expirada, travadas para reivindicação exclusiva.
   *
   * <p>JPQL e não SQL nativo de propósito: a tabela vive no schema {@code webhook} e o
   * {@code hibernate.default_schema} não se aplica a consultas nativas — uma delas quebraria em
   * runtime dependendo do {@code search_path} da conexão.
   *
   * <p>{@code jakarta.persistence.lock.timeout = -2} é o valor que o Hibernate interpreta como
   * {@code SKIP LOCKED} ({@code LockOptions.SKIP_LOCKED}). Sem ele, o {@code PESSIMISTIC_WRITE}
   * faria as réplicas <b>esperarem</b> umas às outras em vez de pegarem conjuntos disjuntos, o que
   * troca a entrega duplicada por uma fila serializada.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      SELECT d FROM DeliveryEntity d
       WHERE d.status IN :statuses
         AND d.nextAttemptAt <= :now
         AND (d.claimedAt IS NULL OR d.claimedAt < :leaseCutoff)
         AND (d.partitionKey IS NULL
              OR NOT EXISTS (SELECT 1 FROM DeliveryEntity emVoo
                              WHERE emVoo.partitionKey = d.partitionKey
                                AND emVoo.id <> d.id
                                AND emVoo.status IN :statuses
                                AND emVoo.claimedAt IS NOT NULL
                                AND emVoo.claimedAt >= :leaseCutoff))
       ORDER BY d.nextAttemptAt ASC
      """)
  List<DeliveryEntity> selectClaimable(
      @Param("statuses") List<DeliveryStatus> statuses,
      @Param("now") Instant now,
      @Param("leaseCutoff") Instant leaseCutoff,
      Limit limit);
}
