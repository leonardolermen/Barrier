package com.barrier.riskengine.history.controller;

import com.barrier.riskengine.history.domain.HistoryEventType;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Registro de um evento de histórico interno para um subject. */
public record RecordHistoryEventRequest(
    @NotNull HistoryEventType eventType, String detail, Instant occurredAt) {}
