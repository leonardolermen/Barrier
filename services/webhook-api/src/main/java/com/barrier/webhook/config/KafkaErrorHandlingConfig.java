package com.barrier.webhook.config;

import com.barrier.webhook.controller.MalformedEventException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * O que acontece quando o consumo falha.
 *
 * <p>Falha transitória (banco fora do ar, deadlock, indisponibilidade momentânea) é retentada com
 * backoff exponencial <b>sem commitar o offset</b>: se a instância morrer no meio, outra retoma do
 * mesmo ponto e a decisão de KYC não some. {@link MalformedEventException} não é retentada — nunca
 * daria certo e prenderia a partição.
 *
 * <p>Esgotadas as tentativas, o evento vai para {@code <tópico>.DLT} em vez de bloquear a partição
 * indefinidamente: uma decisão presa pararia a entrega de <b>todos</b> os tenants. O que salva o
 * evento parado na DLT é a reconciliação, que compara o tópico com as entregas registradas.
 */
@Configuration
public class KafkaErrorHandlingConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

  @Bean
  DefaultErrorHandler assessmentCompletedErrorHandler(
      KafkaTemplate<String, String> kafkaTemplate,
      @Value("${barrier.webhook.consumer.retry-initial:PT2S}") Duration initial,
      @Value("${barrier.webhook.consumer.retry-max-elapsed:PT2M}") Duration maxElapsed) {
    ExponentialBackOff backOff = new ExponentialBackOff(initial.toMillis(), 2.0);
    backOff.setMaxElapsedTime(maxElapsed.toMillis());

    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(kafkaTemplate);

    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            (record, exception) -> {
              log.error(
                  "Evento {} da partição {} não pôde ser processado; indo para a DLT."
                      + " A reconciliação é quem recupera daqui.",
                  record.key(),
                  record.partition(),
                  exception);
              recoverer.accept(record, exception);
            },
            backOff);
    handler.addNotRetryableExceptions(MalformedEventException.class);
    return handler;
  }
}
