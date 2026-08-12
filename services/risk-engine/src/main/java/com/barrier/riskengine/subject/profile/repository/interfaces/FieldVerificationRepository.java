package com.barrier.riskengine.subject.profile.repository.interfaces;

import com.barrier.riskengine.subject.profile.domain.FieldVerification;
import com.barrier.riskengine.subject.profile.domain.VerifiableField;
import com.barrier.riskengine.subject.profile.domain.VerificationChallenge;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Verificações de campo e desafios de OTP.
 *
 * <p>Como todo o resto do cadastro, é escopado por {@code (subject, tenant)}: a verificação feita
 * por um parceiro não vale para outro (ver migration V024/V034).
 */
public interface FieldVerificationRepository {

  /** Substitui a verificação daquele campo — verificar de novo sobrescreve o valor anterior. */
  void save(FieldVerification verification);

  List<FieldVerification> findBySubjectAndTenant(UUID subjectId, String tenantId);

  void saveChallenge(VerificationChallenge challenge);

  /** Desafio mais recente para o campo; é sobre ele que a confirmação age. */
  Optional<VerificationChallenge> findLatestChallenge(
      UUID subjectId, String tenantId, VerifiableField field);

  void updateChallenge(VerificationChallenge challenge);
}
