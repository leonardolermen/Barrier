package com.barrier.riskengine.config;

import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Cria os tópicos do barramento com <b>partições explícitas</b>.
 *
 * <p>Não existia nenhuma declaração de tópico: eles nasciam por auto-criação do broker, com o
 * default — tipicamente <b>uma partição</b>. Uma partição é um teto rígido de escala: num consumer
 * group, cada partição vai para no máximo um consumidor, então <b>um pod da webhook-api consome e
 * todos os outros ficam ociosos</b>. Subir réplica não aumentava throughput nenhum, e nada no
 * sistema apontava — o lag simplesmente crescia num consumidor só.
 *
 * <p><b>Partições ≥ réplicas alvo</b> é a regra. O default de 6 dá folga sobre as 5 réplicas
 * planejadas sem exagerar: partição também custa (memória no broker, arquivo aberto, tempo de
 * rebalance), e partição demais é tão ruim quanto de menos.
 *
 * <p><b>Ordem.</b> A ordem continua garantida <b>por chave de partição</b> — {@code assessmentId}
 * nos eventos de avaliação, {@code subjectId} no comportamental (ver
 * {@code docs/architecture/event-catalog.md}) — e nunca globalmente. Isso já era verdade com uma
 * partição por acidente, não por contrato: com uma partição só, tudo era ordenado, e um consumidor
 * podia ter passado a depender disso sem saber. Aumentar partições torna a garantia real igual à
 * garantia documentada.
 *
 * <p>⚠️ Mudar {@code partitions} depois de o tópico existir <b>não</b> reparticiona: o Kafka só
 * aceita aumentar, e aumentar redistribui as chaves novas sem mover as antigas. Alterar este valor
 * em produção é mudança de contrato, não de config — ver o catálogo de eventos.
 */
@Configuration
public class KafkaTopicsConfig {

  private final int partitions;
  private final short replicationFactor;

  public KafkaTopicsConfig(
      @Value("${barrier.kafka.topic-partitions:6}") int partitions,
      @Value("${barrier.kafka.replication-factor:1}") short replicationFactor) {
    this.partitions = partitions;
    this.replicationFactor = replicationFactor;
  }

  /**
   * PRECISA ser {@code KafkaAdmin.NewTopics}, nunca {@code List<NewTopic>}.
   *
   * <p>O {@link KafkaAdmin} varre o contexto por beans do tipo {@code NewTopic} ou
   * {@code KafkaAdmin.NewTopics}. Um bean {@code List<NewTopic>} nao e nenhum dos dois: ele e
   * <b>ignorado em silencio</b>, e o broker volta a auto-criar cada topico com uma particao — o
   * problema que esta classe existe para resolver, agora escondido atras de uma configuracao que
   * aparenta resolve-lo. Foi assim na primeira versao, com teste unitario verde.
   * {@code KafkaTopicCreationIntegrationTest} e o que prova o wiring.
   */
  @Bean
  public KafkaAdmin.NewTopics barrierTopics() {
    return new KafkaAdmin.NewTopics(topics().toArray(NewTopic[]::new));
  }

  /** Topologia declarada, separada do bean para o teste unitario poder inspeciona-la. */
  List<NewTopic> topics() {
    return List.of(
        topic("barrier.assessment.completed"),
        topic("barrier.subject.risk_level_changed"),
        topic("barrier.behavior.recorded"));
  }

  private NewTopic topic(String name) {
    return TopicBuilder.name(name).partitions(partitions).replicas(replicationFactor).build();
  }
}
