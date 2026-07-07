package com.barrier.webhook.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dispara periodicamente o reprocessamento de entregas vencidas. */
@Component
public class DeliveryRetryScheduler {

  private final WebhookDeliveryService service;

  public DeliveryRetryScheduler(WebhookDeliveryService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${barrier.webhook.retry-delay-ms:5000}")
  public void retry() {
    service.retryDue();
  }
}
