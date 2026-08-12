package com.barrier.riskengine.assessment.repository.interfaces;

import com.barrier.riskengine.assessment.repository.AssessmentEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Spring Data JPA — acesso técnico à tabela {@code assessments}. */
public interface AssessmentJpaRepository extends JpaRepository<AssessmentEntity, UUID> {

  /**
   * Carrega a linha com {@code SELECT ... FOR UPDATE}, para que a comparação de versão feita em
   * seguida não possa ser invalidada por um writer concorrente entre a leitura e a escrita.
   *
   * <p>Sem o lock, a comparação seria só uma checagem otimista sob READ COMMITTED: outra réplica
   * poderia commitar no intervalo e as duas decisões se sobreporiam mesmo assim.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<AssessmentEntity> findWithLockById(UUID id);
}
