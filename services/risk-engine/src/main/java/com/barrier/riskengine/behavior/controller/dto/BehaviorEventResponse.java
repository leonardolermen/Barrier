package com.barrier.riskengine.behavior.controller.dto;

import java.time.Instant;

/** @param duplicate verdadeiro quando o evento já havia sido ingerido (reenvio) */
public record BehaviorEventResponse(
    String eventId, String subjectId, String eventType, Instant occurredAt, boolean duplicate) {}
