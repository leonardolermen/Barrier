package com.barrier.riskengine.behavior.service;

import com.barrier.riskengine.behavior.domain.BehaviorEvent;
import com.barrier.riskengine.behavior.repository.interfaces.BehaviorEventRepository;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingestão de fatos comportamentais e as consultas que as regras vão usar.
 *
 * <p><b>Ingestão não decide nada.</b> Gravar um evento comportamental não dispara reavaliação: o
 * volume aqui é de outra ordem de grandeza (uma transação por compra, não uma por onboarding), e
 * ligar cada fato a uma avaliação completa — com consulta paga de bureau — transformaria o cliente
 * mais ativo no mais caro. O acervo é a <b>fundação</b>; as regras que o lêem são entrega própria
 * (item 7 da Fase 8 do risk-engine-plan), e é lá que a política de disparo será decidida.
 *
 * <p>Desligável em {@code barrier.behavior.enabled}, padrão do {@code rescreening}/{@code assurance}.
 */
@Service
public class BehaviorEventService {

  private static final Logger log = LoggerFactory.getLogger(BehaviorEventService.class);

  private final BehaviorEventRepository repository;
  private final SubjectService subjects;
  private final BehaviorEventPublisher publisher;
  private final boolean enabled;
  private final Duration maxFuture;

  public BehaviorEventService(
      BehaviorEventRepository repository,
      SubjectService subjects,
      BehaviorEventPublisher publisher,
      @Value("${barrier.behavior.enabled:true}") boolean enabled,
      @Value("${barrier.behavior.max-future-skew:PT5M}") Duration maxFuture) {
    this.repository = repository;
    this.subjects = subjects;
    this.publisher = publisher;
    this.enabled = enabled;
    this.maxFuture = maxFuture;
  }

  /**
   * Registra um fato. O subject é resolvido pelo documento (acha-ou-cria, mesmo caminho do intake),
   * e o vínculo com o tenant é garantido — sem ele, o parceiro conseguiria escrever histórico sobre
   * um cliente que não é dele.
   *
   * @return vazio quando o evento já tinha sido ingerido (idempotência por {@code sourceEventId})
   */
  @Transactional
  public Optional<BehaviorEvent> record(
      String tenantId,
      String documentType,
      String document,
      String name,
      String eventType,
      Instant occurredAt,
      String payload,
      String sourceEventId) {
    if (!enabled) {
      return Optional.empty();
    }
    // Relógio do parceiro adiantado envenenaria toda janela deslizante construída sobre estes
    // eventos: um fato "do futuro" fica eternamente dentro de qualquer janela recente.
    Instant limite = Instant.now().plus(maxFuture);
    Instant quando = occurredAt.isAfter(limite) ? Instant.now() : occurredAt;
    if (occurredAt.isAfter(limite)) {
      log.warn(
          "Evento comportamental do tenant {} com occurred_at no futuro ({}); usando o instante de"
              + " recebimento",
          tenantId,
          occurredAt);
    }

    Subject subject = subjects.findOrCreate(documentType, document, name);
    subjects.link(tenantId, subject.id());

    Optional<BehaviorEvent> gravado =
        repository.append(
            BehaviorEvent.of(tenantId, subject.id(), eventType, quando, payload, sourceEventId));
    gravado.ifPresent(publisher::publish);
    return gravado;
  }

  /** Eventos recentes do cliente. Escopado por tenant, como todo o resto. */
  @Transactional(readOnly = true)
  public List<BehaviorEvent> recent(UUID subjectId, String tenantId, Duration window, int limit) {
    return repository.findRecent(subjectId, tenantId, Instant.now().minus(window), limit);
  }

  /** Contagem por tipo na janela — a forma que uma regra comportamental vai consumir. */
  @Transactional(readOnly = true)
  public long countSince(UUID subjectId, String tenantId, String eventType, Duration window) {
    return repository.countByTypeSince(
        subjectId, tenantId, eventType, Instant.now().minus(window));
  }
}
