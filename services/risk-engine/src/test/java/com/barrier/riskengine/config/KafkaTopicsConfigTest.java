package com.barrier.riskengine.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

/**
 * Os tópicos declarados precisam ser exatamente os que os publicadores usam, e ter partições
 * suficientes para o número de réplicas alvo.
 *
 * <p><b>Por que partição explícita importa.</b> Sem {@link NewTopic}, o tópico nasce com o default
 * do broker — tipicamente <b>uma</b> partição. Com uma partição e um consumer group, um único pod
 * consome e os demais ficam ociosos: a webhook-api não escala horizontalmente, por mais réplicas
 * que subam, e nada no sistema reclama. É um teto silencioso.
 *
 * <p>A verificação de nomes por reflexão existe porque a config e os publicadores são a mesma
 * informação em dois lugares. Divergir significa criar um tópico que ninguém publica e publicar
 * num tópico criado pelo default do broker — ou seja, exatamente o problema que esta config veio
 * resolver, de volta e mais difícil de ver.
 */
class KafkaTopicsConfigTest {

  private static final int REPLICAS_ALVO = 5;

  private final KafkaTopicsConfig config = new KafkaTopicsConfig(6, (short) 1);

  @Test
  void declaraExatamenteOsTopicosQueOsPublicadoresUsam() throws Exception {
    Set<String> declarados =
        topics().stream().map(NewTopic::name).collect(Collectors.toUnmodifiableSet());

    assertThat(declarados)
        .containsExactlyInAnyOrder(
            eventType("com.barrier.riskengine.assessment.service.AssessmentEventPublisher"),
            eventType("com.barrier.riskengine.riskstate.service.RiskLevelChangeEventPublisher"),
            eventType("com.barrier.riskengine.behavior.service.BehaviorEventPublisher"));
  }

  @Test
  void temParticoesSuficientesParaAsReplicasAlvo() {
    assertThat(topics())
        .allSatisfy(
            topic ->
                assertThat(topic.numPartitions())
                    .as(
                        "tópico %s com %d partições limita o consumo a %d pods",
                        topic.name(), topic.numPartitions(), topic.numPartitions())
                    .isGreaterThanOrEqualTo(REPLICAS_ALVO));
  }

  List<NewTopic> topics() {
    return config.topics();
  }

  /** Lê a constante {@code EVENT_TYPE} (package-private) do publicador. */
  private static String eventType(String className) throws Exception {
    Field field = Class.forName(className).getDeclaredField("EVENT_TYPE");
    field.setAccessible(true);
    return (String) field.get(null);
  }
}
