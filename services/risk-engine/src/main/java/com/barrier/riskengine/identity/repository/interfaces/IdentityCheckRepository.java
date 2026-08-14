package com.barrier.riskengine.identity.repository.interfaces;

import com.barrier.riskengine.identity.domain.IdentityCheck;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Repositório de domínio das verificações de identidade. */
public interface IdentityCheckRepository {

  IdentityCheck save(IdentityCheck check);

  List<IdentityCheck> findByAssessmentId(String assessmentId);

  /**
   * Verificação anterior que pode ser reaproveitada por uma avaliação nova, se existir.
   *
   * <p>Escopada ao tenant de propósito: dado objetivo de bureau é compartilhável em tese, mas
   * cruzar tenants aqui repetiria o erro que o ADR-0012 corrigiu no cadastro. Fica como opt-in
   * futuro, com ADR próprio.
   *
   * <p>Não devolve check que já é reuso ({@code reused_from_id IS NULL}): reuso de reuso
   * encadearia a procedência e afastaria a decisão da consulta real sem que a distância
   * aparecesse em lugar nenhum.
   */
  Optional<IdentityCheck> findReusable(
      String tenantId, String documentType, String documentDigits, String name, Instant notBefore);
}
