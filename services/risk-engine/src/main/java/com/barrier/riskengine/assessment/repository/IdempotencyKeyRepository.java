package com.barrier.riskengine.assessment.repository;

import com.barrier.riskengine.assessment.domain.AssessmentId;
import com.barrier.riskengine.assessment.domain.IdempotencyReservation;
import java.time.Duration;
import java.util.Optional;

/** Chaves de idempotência do intake, por tenant. */
public interface IdempotencyKeyRepository {

  /**
   * Tenta tomar a posse da chave para esta requisição.
   *
   * <p>Devolve uma reserva {@code fresh} quando a chave não existia ou já estava fora da janela — a
   * requisição deve então criar a avaliação e chamar {@link #bind}. Caso contrário devolve a
   * reserva existente, para o chamador decidir entre repetir a resposta e recusar.
   *
   * @param window por quanto tempo uma chave usada continua valendo como repetição
   */
  IdempotencyReservation reserve(String tenantId, String key, String requestHash, Duration window);

  /** Liga a chave à avaliação criada; a partir daí a repetição tem o que devolver. */
  void bind(String tenantId, String key, AssessmentId assessmentId);

  /** Devolve a chave ao estado livre — a submissão que a reservou não completou. */
  void release(String tenantId, String key);

  Optional<IdempotencyReservation> find(String tenantId, String key);

  /** Remove chaves fora da janela. Só higiene de tabela: a janela já é aplicada na reserva. */
  int purgeOlderThan(Duration age);
}
