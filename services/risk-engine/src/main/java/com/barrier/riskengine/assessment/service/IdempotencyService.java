package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;
import com.barrier.riskengine.assessment.domain.IdempotencyReservation;
import com.barrier.riskengine.assessment.repository.interfaces.IdempotencyKeyRepository;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posse das chaves de idempotência do intake.
 *
 * <p>Todas as operações rodam em transação <b>própria</b> ({@code REQUIRES_NEW}). A reserva precisa
 * estar commitada antes de a avaliação ser criada — é ela que faz duas requisições concorrentes
 * disputarem uma linha só —, e a liberação precisa sobreviver ao rollback da submissão que falhou,
 * senão a chave ficaria travada até o fim da janela por um erro que nem chegou a criar avaliação.
 */
@Service
public class IdempotencyService {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

  private final IdempotencyKeyRepository repository;
  private final Duration window;

  public IdempotencyService(
      IdempotencyKeyRepository repository,
      @Value("${barrier.assessment.idempotency-window}") Duration window) {
    this.repository = repository;
    this.window = window;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IdempotencyReservation reserve(String tenantId, String key, String requestHash) {
    return repository.reserve(tenantId, key, requestHash, window);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void bind(String tenantId, String key, AssessmentId assessmentId) {
    repository.bind(tenantId, key, assessmentId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void release(String tenantId, String key) {
    repository.release(tenantId, key);
  }

  /**
   * Limpeza das chaves fora da janela. Não afeta a correção — a reserva já ignora chave expirada —,
   * só evita que a tabela cresça para sempre com linhas que ninguém mais consulta.
   */
  @Scheduled(cron = "${barrier.assessment.idempotency-purge-cron}")
  public void purgeExpired() {
    int removed = repository.purgeOlderThan(window);
    if (removed > 0) {
      log.info("Chaves de idempotência expiradas removidas: {}", removed);
    }
  }
}
