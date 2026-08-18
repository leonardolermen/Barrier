package com.barrier.riskengine.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barrier.riskengine.behavior.domain.BehaviorEvent;
import com.barrier.riskengine.behavior.repository.interfaces.BehaviorEventRepository;
import com.barrier.riskengine.behavior.service.BehaviorEventPublisher;
import com.barrier.riskengine.behavior.service.BehaviorEventService;
import com.barrier.riskengine.subject.domain.Subject;
import com.barrier.riskengine.subject.service.SubjectService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Ingestão comportamental: idempotência, vínculo de tenant e defesa contra relógio adiantado. */
@ExtendWith(MockitoExtension.class)
class BehaviorEventServiceTest {

  private static final String TENANT = "acme";

  @Mock BehaviorEventRepository repository;
  @Mock SubjectService subjects;
  @Mock BehaviorEventPublisher publisher;

  private BehaviorEventService service;
  private Subject subject;

  @BeforeEach
  void setUp() {
    service =
        new BehaviorEventService(repository, subjects, publisher, true, Duration.ofMinutes(5));
    subject = Subject.create("CPF", "11144477735", "Fulano");
    org.mockito.Mockito.lenient()
        .when(subjects.findOrCreate(any(), any(), any()))
        .thenReturn(subject);
  }

  @Test
  void grava_o_fato_vincula_o_tenant_e_publica() {
    when(repository.append(any())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));

    Optional<BehaviorEvent> gravado =
        service.record(
            TENANT, "CPF", "11144477735", "Fulano", "transaction", Instant.now(), "{}", "evt-1");

    assertThat(gravado).isPresent();
    verify(subjects).link(TENANT, subject.id());
    verify(publisher).publish(gravado.get());
  }

  /** Reprocessamento da fila do parceiro não pode contar a mesma transação duas vezes. */
  @Test
  void evento_duplicado_nao_publica() {
    when(repository.append(any())).thenReturn(Optional.empty());

    Optional<BehaviorEvent> gravado =
        service.record(
            TENANT, "CPF", "11144477735", "Fulano", "transaction", Instant.now(), "{}", "evt-1");

    assertThat(gravado).isEmpty();
    verify(publisher, never()).publish(any());
  }

  /** Fato "do futuro" ficaria eternamente dentro de qualquer janela deslizante. */
  @Test
  void relogio_adiantado_do_parceiro_e_corrigido_para_o_recebimento() {
    when(repository.append(any())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
    Instant futuro = Instant.now().plus(2, ChronoUnit.DAYS);

    BehaviorEvent gravado =
        service
            .record(TENANT, "CPF", "11144477735", "Fulano", "login", futuro, null, "evt-2")
            .orElseThrow();

    assertThat(gravado.occurredAt()).isBefore(futuro);
  }

  @Test
  void desligado_nao_grava_nada() {
    var desligado =
        new BehaviorEventService(repository, subjects, publisher, false, Duration.ofMinutes(5));

    assertThat(
            desligado.record(
                TENANT, "CPF", "11144477735", "Fulano", "login", Instant.now(), null, "evt-3"))
        .isEmpty();
    verify(repository, never()).append(any());
  }
}
