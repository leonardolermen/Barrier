package com.barrier.riskengine.assessment.repository.interfaces;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;
import com.barrier.riskengine.assessment.domain.assessment.AssessmentOrigin;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

  /**
   * Avaliações <b>criadas</b> na janela. Mede intake — é a série de que sai o alerta de silêncio
   * (parceiro que parou de mandar), que nenhum limiar fixo pega.
   */
  long countCreatedBetween(Instant from, Instant to);

  /**
   * Avaliações <b>concluídas</b> na janela, agrupadas por status. É a série de que saem os alertas
   * de deriva de taxa (aprovação automática que dispara, recusa que dispara) — o sinal que pega
   * regra mal calibrada, provider devolvendo lixo e fraude em escala com a mesma métrica.
   */
  java.util.Map<com.barrier.riskengine.assessment.domain.assessment.AssessmentStatus, Long>
      countCompletedByStatusBetween(Instant from, Instant to);

  /**
   * Avaliações concluídas como APROVADO na janela <b>sem</b> passar por decisão humana
   * ({@code reviewed_at IS NULL}). Separa aprovação do motor de aprovação da mesa: as duas derivam
   * por causas diferentes e alertar sobre a soma esconderia as duas.
   */
  long countAutoApprovedBetween(Instant from, Instant to);

  /**
   * Já existe avaliação <b>ainda não concluída</b> para aquele {@code (subject, tenant)}?
   *
   * <p>Existe para o gatilho de alteração de cadastro: uma avaliação em análise ainda vai ler o
   * cadastro quando for processada, então mudar o cadastro enquanto ela existe não pede avaliação
   * nova — a que está na fila já vai enxergar o valor novo.
   */
  boolean existsPendingBySubject(UUID subjectId, String tenantId);

  Assessment save(Assessment assessment);

  Optional<Assessment> findById(AssessmentId id);

  /**
   * Última avaliação <b>concluída</b> daquele {@code (subject, tenant)}, mais recente primeiro.
   *
   * <p>Existe para o fallback da projeção de risco corrente: subject avaliado antes da V041, ou
   * criado entre a migration e a primeira avaliação nova, não tem linha na projeção. Não é o
   * caminho normal de leitura — a projeção é.
   */
  Optional<Assessment> findLastCompleted(UUID subjectId, String tenantId);

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

  /**
   * Já existe avaliação de {@code origin}, para aquele {@code (subject, tenant)}, criada dentro
   * de {@code window} (contado a partir de agora)?
   *
   * <p>Base do dedup de reavaliação do assurance: vinte desfechos de biometria seguidos não podem
   * virar vinte avaliações — só a primeira dentro da janela deve disparar. Ver Javadoc de {@code
   * AssuranceReassessmentTrigger}.
   */
  boolean existsRecentByOriginAndSubject(
      UUID subjectId, String tenantId, AssessmentOrigin origin, Duration window);
}
