package com.barrier.riskengine.behavior.controller;

import com.barrier.riskengine.behavior.controller.dto.BehaviorEventRequest;
import com.barrier.riskengine.behavior.controller.dto.BehaviorEventResponse;
import com.barrier.riskengine.behavior.domain.BehaviorEvent;
import com.barrier.riskengine.behavior.service.BehaviorEventService;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestão de fatos comportamentais.
 *
 * <p>Responde <b>202</b>: o fato foi aceito e gravado, mas nada foi decidido a partir dele — as
 * regras comportamentais são entrega própria. Devolver 201 sugeriria que algo foi avaliado.
 *
 * <p>Reenvio do mesmo {@code sourceEventId} também responde 202, com {@code duplicate=true}: para o
 * parceiro, reenviar precisa ser seguro e barato, senão ele evita reenviar e perde fato de verdade.
 */
@RestController
@RequestMapping("/v1/behavior-events")
public class BehaviorEventController {

  private final BehaviorEventService service;

  public BehaviorEventController(BehaviorEventService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<BehaviorEventResponse> record(
      AuthenticatedTenant tenant, @Valid @RequestBody BehaviorEventRequest request) {
    Optional<BehaviorEvent> gravado =
        service.record(
            tenant.id(),
            request.documentType(),
            request.document(),
            request.name(),
            request.eventType(),
            request.occurredAt(),
            request.payload(),
            request.sourceEventId());

    return gravado
        .map(
            e ->
                ResponseEntity.accepted()
                    .body(
                        new BehaviorEventResponse(
                            e.id().toString(),
                            e.subjectId().toString(),
                            e.eventType(),
                            e.occurredAt(),
                            false)))
        .orElseGet(
            () ->
                ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(
                        new BehaviorEventResponse(
                            null, null, request.eventType(), request.occurredAt(), true)));
  }
}
