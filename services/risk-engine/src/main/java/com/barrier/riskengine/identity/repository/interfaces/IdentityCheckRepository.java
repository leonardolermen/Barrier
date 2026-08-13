package com.barrier.riskengine.identity.repository.interfaces;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import java.util.List;

/** Repositório de domínio das verificações de identidade. */
public interface IdentityCheckRepository {

  IdentityCheck save(IdentityCheck check);

  List<IdentityCheck> findByAssessmentId(String assessmentId);
}
