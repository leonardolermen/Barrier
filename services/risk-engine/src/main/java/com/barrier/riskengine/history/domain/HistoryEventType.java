package com.barrier.riskengine.history.domain;

/** Tipo de evento de histórico interno do subject, usado por {@code HistoryRiskRule}. */
public enum HistoryEventType {
  CHARGEBACK,
  PIX_RETURNED,
  FRAUD_REPORT,
  ACCOUNT_CLOSED_FRAUD
}
