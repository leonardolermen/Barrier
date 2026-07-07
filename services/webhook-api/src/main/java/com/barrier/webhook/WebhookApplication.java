package com.barrier.webhook;

import com.barrier.webhook.config.WebhookProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Webhook API: consome {@code barrier.assessment.completed} do Kafka e entrega o resultado no
 * endpoint do cliente, com assinatura HMAC, retentativas e rastreio de entregas.
 *
 * <p>Escaneia apenas {@code com.barrier.webhook} — usa de {@code commons} só o contrato de
 * evento (sem puxar os beans de outbox).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebhookApplication.class, args);
  }
}
