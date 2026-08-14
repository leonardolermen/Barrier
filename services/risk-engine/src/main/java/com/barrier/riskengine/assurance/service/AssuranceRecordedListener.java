package com.barrier.riskengine.assurance.service;

import com.barrier.riskengine.assurance.domain.AssuranceCheck;

/**
 * Notificação de que uma verificação de documentoscopia/biometria acabou de ser gravada.
 *
 * <p>Existe para inverter a dependência: uma verificação de assurance tem de disparar uma
 * reavaliação (o desfecho pode mudar a decisão tomada no onboarding), mas {@code assurance} não
 * pode conhecer quem reavalia. Reavaliar é submeter pelo {@code AssessmentService}, que já
 * depende de {@code risk}, que por sua vez já depende de {@code assurance}
 * ({@code IdentityAssuranceRiskRule}) — se {@code assurance} chamasse a reavaliação direto,
 * fecharia o ciclo {@code assurance → assessment/rescreening → risk → assurance} que a regra
 * {@code sem_ciclos_entre_modulos} proíbe. Mesmo padrão de {@code WatchlistImportListener}: quem
 * reage implementa isto e depende de {@code assurance}, nunca o contrário.
 */
public interface AssuranceRecordedListener {

  /**
   * Chamado depois de o desfecho já estar gravado. Dispara em <b>qualquer</b> desfecho — inclusive
   * FAIL e INCONCLUSIVE/UNAVAILABLE: uma prova de vida que falhou é o insumo que mais muda a
   * decisão tomada no onboarding, e restringir o gatilho ao PASS deixaria a avaliação parada
   * exatamente no caso de fraude.
   *
   * <p>Uma exceção daqui não desfaz a gravação — o {@code AssuranceService} isola cada listener.
   */
  void onRecorded(AssuranceCheck check);
}
