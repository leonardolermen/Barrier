package com.barrier.riskengine.identity.service;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.IdentityProvenance;
import com.barrier.riskengine.identity.repository.interfaces.IdentityCheckRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Resolve a procedência da verificação de identidade de uma avaliação — se o check foi à rede ou
 * reaproveitou uma consulta anterior, e quando essa consulta de fato aconteceu.
 *
 * <p>Consumido tanto pelo {@code GET /v1/assessments/{id}} quanto pelo evento {@code
 * barrier.assessment.completed}: o parceiro que decide pelo webhook não busca o GET, e uma
 * decisão apoiada numa verificação de ontem é informação que ele precisa para a própria trilha
 * dele.
 */
@Service
public class IdentityProvenanceService {

  private final IdentityCheckRepository repository;

  public IdentityProvenanceService(IdentityCheckRepository repository) {
    this.repository = repository;
  }

  /**
   * Procedência do check desta avaliação, se houver algum. Uma avaliação pode não ter check ainda
   * (processamento assíncrono em andamento) ou nunca ter tido (documento sem cadeia de bureau).
   */
  public Optional<IdentityProvenance> forAssessment(String assessmentId) {
    return repository.findByAssessmentId(assessmentId).stream().findFirst().map(this::resolve);
  }

  private IdentityProvenance resolve(IdentityCheck check) {
    if (!check.isReused()) {
      return new IdentityProvenance(false, check.checkedAt());
    }
    // reused_from_id aponta para a consulta que de fato foi à rede; checkedAt do check reaproveitado
    // é só o momento em que esta avaliação decidiu, não quando o bureau respondeu.
    IdentityCheck original = repository.findById(check.reusedFromId()).orElse(check);
    return new IdentityProvenance(true, original.checkedAt());
  }
}
