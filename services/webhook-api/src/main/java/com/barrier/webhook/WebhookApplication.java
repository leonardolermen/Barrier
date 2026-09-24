package com.barrier.webhook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Webhook API: consome {@code barrier.assessment.completed} do Kafka e aciona a entrega do
 * resultado no endpoint do cliente — a máquina de entrega (HMAC, retentativas, rastreio) é a
 * biblioteca {@code com.barrier:webhook-delivery}, ligada por autoconfiguration.
 *
 * <p>Escaneia apenas {@code com.barrier.webhook} — usa de {@code commons} só o contrato de
 * evento (sem puxar os beans de outbox). {@code @EnableScheduling} agora também é exigido pela
 * lib, que roda o retry de entrega em {@code @Scheduled}.
 */
@SpringBootApplication
@EnableScheduling
public class WebhookApplication {

  public static void main(String[] args) {
    SpringApplication.run(WebhookApplication.class, args);
  }
}
