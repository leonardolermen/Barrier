package com.barrier.riskengine.behavior.service;

import com.barrier.commons.outbox.OutboxRecorder;
import com.barrier.riskengine.behavior.domain.BehaviorEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publica {@code barrier.behavior.recorded} na outbox, na mesma transação da gravação do fato.
 *
 * <p><b>Partição por subject, não por avaliação — e não pelo documento.</b> A lição do {@code tzofe}
 * é particionar por entidade: toda a atividade de um cliente cai na mesma partição, o que preserva
 * a ordem dos fatos dele e permite que um consumidor mantenha estado local <i>sem coordenação</i>.
 * O ecossistema Origem usa o campo {@code document} (CPF/CNPJ) para isso.
 *
 * <p>Aqui a chave é o <b>subjectId</b>, e a diferença é deliberada: chave de Kafka aparece em log de
 * broker, métrica de consumer lag e ferramenta de inspeção de tópico — lugares sem o controle de
 * acesso que o banco tem. Pôr CPF ali espalharia dado pessoal por toda a malha de observabilidade
 * para ganhar exatamente nada: o {@code subjectId} é único por documento (ADR-0011) e dá a mesma
 * garantia de ordenação por entidade.
 *
 * <p>Este é o <b>terceiro</b> tópico do barramento, o que dispara o gatilho do catálogo de eventos
 * (fila-origem F9) — ver {@code docs/architecture/event-catalog.md}.
 */
@Component
public class BehaviorEventPublisher {

  static final String EVENT_TYPE = "barrier.behavior.recorded";
  static final int EVENT_VERSION = 1;

  private final OutboxRecorder outbox;
  private final ObjectMapper objectMapper;

  public BehaviorEventPublisher(OutboxRecorder outbox, ObjectMapper objectMapper) {
    this.outbox = outbox;
    this.objectMapper = objectMapper;
  }

  public void publish(BehaviorEvent event) {
    outbox.record(
        event.subjectId().toString(),
        EVENT_TYPE,
        EVENT_VERSION,
        objectMapper.writeValueAsString(BehaviorRecordedPayload.from(event)));
  }
}
