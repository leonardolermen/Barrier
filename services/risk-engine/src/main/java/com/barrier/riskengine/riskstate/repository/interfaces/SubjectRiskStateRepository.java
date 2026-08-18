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

  /**
   * Candidatos a reavaliação periódica: clientes cuja última decisão é mais antiga que
   * { menorIntervalo}, mais antigos primeiro.
   *
   * <p><b>Pré-filtro grosseiro de propósito.</b> A consulta usa o MENOR intervalo da tabela do
   * ADR-0019 (o do pior nível de risco) e devolve tudo que passou dele; quem decide de fato é a
   * {@code ReassessmentPolicy}, aplicando o intervalo do nível de cada cliente. Replicar os quatro
   * prazos aqui em SQL criaria uma segunda cópia da política — e duas cópias divergem.
   */
  java.util.List<SubjectRiskState> findDueForPeriodicReview(
      java.time.Duration menorIntervalo, int limit);
}
