package com.barrier.riskengine.risk.service;

import com.barrier.riskengine.risk.domain.model.RiskScore;
import com.barrier.riskengine.risk.repository.interfaces.RiskScoreRepository;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Leitura da trilha de pontuação — o portão do módulo {@code risk} para quem precisa ler uma decisão
 * já tomada.
 *
 * <p>Existe para que o módulo {@code replay} não alcance {@code RiskScoreRepository} direto: o
 * service é o portão do módulo, mesmo padrão de {@code AssessmentService.existsRecentByOriginAndSubject}
 * e de {@link com.barrier.riskengine.identity.service.IdentityProvenanceService}.
 */
@Service
public class RiskScoreQueryService {

  private final RiskScoreRepository repository;

  public RiskScoreQueryService(RiskScoreRepository repository) {
    this.repository = repository;
  }

  /**
   * A pontuação que <b>vale</b> para a avaliação: a mais recente.
   *
   * <p>Uma avaliação retentada deixa mais de uma linha em {@code risk_scores} — a última é a que
   * produziu o desfecho gravado no assessment. Ordenar aqui, e não no chamador, evita que cada
   * consumidor invente a própria regra de desempate.
   */
  public Optional<RiskScore> latestFor(String assessmentId) {
    return repository.findByAssessmentId(assessmentId).stream()
        .max(Comparator.comparing(RiskScore::scoredAt));
  }
}
