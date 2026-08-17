package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.assessment.Assessment;

/**
 * Reage a uma avaliação que acabou de ter desfecho — seja pelo motor, seja por decisão humana.
 *
 * <p><b>Por que a interface mora aqui e não no módulo que a implementa.</b> O consumidor natural é
 * a projeção de risco corrente ({@code riskstate}), que precisa de {@code Assessment} e de
 * {@code RiskLevel}. Se {@code assessment} chamasse aquele serviço direto, fecharia dois ciclos de
 * módulo — {@code assessment → riskstate → assessment} e, via {@code RiskLevel},
 * {@code risk → riskstate → risk} — e o ArchUnit ({@code sem_ciclos_entre_modulos}) barra ambos.
 * Declarar a interface no lado que <i>emite</i> o fato e deixar quem reage implementá-la é o mesmo
 * padrão de inversão já usado em {@code AssuranceRecordedListener} e no
 * {@code WatchlistImportListener}.
 *
 * <p>Chamado <b>dentro</b> da transação que grava o desfecho: quem reage a este fato está
 * projetando estado, não disparando efeito externo — se a avaliação não commitar, a reação também
 * não pode ter acontecido.
 */
public interface AssessmentCompletedListener {

  /**
   * @param score total apurado pelo motor; {@code null} quando o desfecho veio de decisão humana,
   *     que não recalcula score — nesse caso quem implementa preserva o que já tinha
   * @param engineVersion versão do motor que apurou o score; {@code null} pelo mesmo motivo
   */
  void onCompleted(Assessment assessment, Integer score, String engineVersion);
}
