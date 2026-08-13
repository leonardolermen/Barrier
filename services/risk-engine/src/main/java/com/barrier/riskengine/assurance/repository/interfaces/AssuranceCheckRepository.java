package com.barrier.riskengine.assurance.repository.interfaces;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;
import com.barrier.riskengine.assurance.domain.AssuranceKind;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resultados de documentoscopia e biometria — nunca as imagens (ADR-0016). */
public interface AssuranceCheckRepository {

  void save(AssuranceCheck check);

  /**
   * Verificação mais recente daquele tipo. É a que vale: refazer a biometria depois de uma falha é
   * o fluxo normal (foto ruim), e a decisão tem de olhar a última tentativa, não a pior.
   */
  Optional<AssuranceCheck> findLatest(UUID subjectId, String tenantId, AssuranceKind kind);

  /** Histórico completo: as tentativas anteriores continuam na trilha para auditoria. */
  List<AssuranceCheck> findAll(UUID subjectId, String tenantId);

  /**
   * Quantas verificações daquele tipo aconteceram dentro de {@code window} (contado a partir de
   * agora). Conta no banco, não materializa o histórico inteiro — este método roda no caminho
   * quente de toda avaliação (ver {@code AssuranceService.attempts}).
   */
  long countRecent(UUID subjectId, String tenantId, AssuranceKind kind, Duration window);

  /**
   * Reivindica um lote de checks {@code BIOMETRIC} ainda {@code PENDING} cuja posse está livre ou
   * vencida, para o {@code AssuranceResultPoller} consultar o provedor <b>fora</b> desta
   * transação — mesmo padrão de {@code OutboxRepository.claimPending}: {@code FOR UPDATE SKIP
   * LOCKED} garante que réplicas concorrentes peguem conjuntos disjuntos, e a lease ({@code
   * claimed_at}) é o que permite que a chamada ao provedor aconteça fora do lock.
   */
  List<AssuranceCheck> claimPendingBiometric(int limit, Duration lease);
}
