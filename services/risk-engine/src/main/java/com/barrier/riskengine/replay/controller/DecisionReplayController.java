package com.barrier.riskengine.replay.controller;

import com.barrier.riskengine.assessment.domain.assessment.AssessmentId;
import com.barrier.riskengine.replay.controller.dto.ReplayDtoMapper;
import com.barrier.riskengine.replay.controller.dto.ReplayResponse;
import com.barrier.riskengine.replay.domain.ReplayMode;
import com.barrier.riskengine.replay.service.DecisionReplayService;
import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replay de uma decisão já tomada — o dossiê que uma fiscalização pede, e a resposta a "o motor de
 * hoje decidiria o mesmo?".
 *
 * <p>É {@code POST} e não {@code GET} porque o modo {@code CURRENT_ENGINE} <b>executa</b> as regras;
 * não altera nada (ver {@code DecisionReplayService}), mas não é uma leitura barata e cacheável, e
 * o verbo deixa espaço para um corpo com sobreposições de política quando o shadow mode entrar.
 *
 * <p>Escopado por tenant como o resto de {@code /v1/assessments}: avaliação de outro parceiro
 * responde 404, nunca 403 — 403 confirmaria que o id existe.
 */
@RestController
@RequestMapping("/v1/assessments")
public class DecisionReplayController {

  private final DecisionReplayService service;

  public DecisionReplayController(DecisionReplayService service) {
    this.service = service;
  }

  @Operation(
      summary = "Replay da decisão de uma avaliação",
      description =
          "AS_DECIDED (padrão) monta o dossiê do que foi decidido a partir do que está gravado — "
              + "evidência exata, todas as regras com desfecho e parâmetro efetivo da época, "
              + "versões das listas — e reconfere a aritmética contra risk_scores. "
              + "CURRENT_ENGINE reexecuta as regras atuais sobre a MESMA evidência gravada (sem "
              + "nenhuma consulta a bureau) e aponta a diferença regra a regra. "
              + "Insumo que não pôde ser reconstruído aparece em `gaps`, e a regra afetada vem como "
              + "NOT_REPLAYABLE — nunca como se tivesse passado.")
  @PostMapping("/{id}/replay")
  public ResponseEntity<ReplayResponse> replay(
      AuthenticatedTenant tenant,
      @PathVariable String id,
      @RequestParam(name = "mode", required = false, defaultValue = "AS_DECIDED") ReplayMode mode) {
    return ResponseEntity.ok(
        ReplayDtoMapper.toResponse(service.replay(AssessmentId.of(id), tenant.id(), mode)));
  }
}
