package com.barrier.riskengine.riskstate.repository.interfaces;

import com.barrier.riskengine.riskstate.domain.SubjectRiskState;
import java.util.Optional;
import java.util.UUID;

/**
 * Acesso à projeção de risco corrente.
 *
 * <p>Não existe assinatura que aceite só o {@code subjectId} — mesma defesa por tipo que o
 * {@code SubjectProfileRepository} adotou depois do vazamento que a V024 corrigiu: sem o tenant na
 * assinatura, a leitura entre parceiros vira um esquecimento em vez de um erro de compilação.
 */
public interface SubjectRiskStateRepository {

  Optional<SubjectRiskState> find(UUID subjectId, String tenantId);

  SubjectRiskState save(SubjectRiskState state);
}
