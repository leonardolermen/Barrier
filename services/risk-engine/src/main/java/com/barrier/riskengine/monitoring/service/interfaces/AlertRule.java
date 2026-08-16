package com.barrier.riskengine.monitoring.service.interfaces;

import com.barrier.riskengine.monitoring.domain.Alert;
import com.barrier.riskengine.monitoring.service.PipelineSnapshot;
import java.util.Optional;

/**
 * Uma regra de alerta sobre o pipeline. Strategy pelo mesmo motivo de {@code RiskRule}: adicionar
 * alerta passa a ser adicionar um bean, sem tocar no avaliador.
 *
 * <p>O snapshot chega pronto — a regra não consulta o banco. Com dezenove regras (o número que o
 * ecossistema Origem tem hoje), uma query por regra viraria dezenove varreduras por ciclo sobre a
 * tabela mais quente do sistema, e o monitoramento passaria a ser causa de incidente.
 */
public interface AlertRule {

  /** Código estável, no vocabulário do Origem ({@code backlog_analise}, {@code vol_hora_baixo}). */
  String code();

  Optional<Alert> evaluate(PipelineSnapshot snapshot);
}
