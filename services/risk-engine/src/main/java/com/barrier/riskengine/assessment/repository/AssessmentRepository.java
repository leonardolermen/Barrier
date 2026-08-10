package com.barrier.riskengine.assessment.repository;

import com.barrier.riskengine.assessment.domain.Assessment;
import com.barrier.riskengine.assessment.domain.AssessmentId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Repositório de domínio de avaliações. O {@code service} depende desta interface, não da
 * implementação JPA.
 */
public interface AssessmentRepository {

  /** Quantas avaliações aguardam processamento. Alimenta a métrica de fila. */
  long countPending();

  /**
   * Instante de criação da avaliação pendente mais antiga; {@code null} se não há nenhuma. É a
   * medida que revela pipeline travado — ver {@code PipelineHealthMetrics}.
   */
  java.time.Instant oldestPendingCreatedAt();

  Assessment save(Assessment assessment);

  Optional<Assessment> findById(AssessmentId id);

  /**
   * Reivindica avaliações pendentes para processamento exclusivo desta instância, mais antigas
   * primeiro, e devolve os ids reivindicados.
   *
   * <p>Devolve ids e não agregados de propósito: a posse é tomada numa transação curta, e o
   * processamento — que faz chamadas HTTP e pode levar segundos — acontece fora dela, carregando
   * cada avaliação quando for a vez. Devolver os agregados convidaria a manter a transação aberta
   * durante todo o lote, que é exatamente o problema anterior.
   *
   * @param lease por quanto tempo a posse vale antes de a avaliação voltar a ser reivindicável
   */
  List<AssessmentId> claimPending(int limit, Duration lease);
}
