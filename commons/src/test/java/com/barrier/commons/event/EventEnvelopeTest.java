package com.barrier.commons.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

  @Test
  void of_geraEventIdEInstante() {
    var envelope = EventEnvelope.of("barrier.assessment.completed", "abc-123", 1, "{}");

    assertThat(envelope.eventId()).isNotNull();
    assertThat(envelope.occurredAt()).isNotNull();
    assertThat(envelope.type()).isEqualTo("barrier.assessment.completed");
    assertThat(envelope.assessmentId()).isEqualTo("abc-123");
  }

  @Test
  void rejeitaVersaoInvalida() {
    assertThatThrownBy(() -> EventEnvelope.of("t", "id", 0, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
