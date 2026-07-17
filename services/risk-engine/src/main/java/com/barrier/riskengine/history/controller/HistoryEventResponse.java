package com.barrier.riskengine.history.controller;

import com.barrier.riskengine.history.domain.SubjectHistoryEvent;
import java.time.Instant;

public record HistoryEventResponse(String eventType, String detail, Instant occurredAt) {

  static HistoryEventResponse of(SubjectHistoryEvent event) {
    return new HistoryEventResponse(
        event.eventType().name(), event.detail(), event.occurredAt());
  }
}
