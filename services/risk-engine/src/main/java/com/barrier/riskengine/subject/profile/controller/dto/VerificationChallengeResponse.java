package com.barrier.riskengine.subject.profile.controller.dto;

/**
 * Confirmação de que o desafio foi emitido.
 *
 * <p>Devolve o id do desafio e <b>nunca</b> o código: a resposta vai para quem chamou a API, e o
 * código precisa chegar por outro canal — é essa separação que faz dele prova de posse.
 */
public record VerificationChallengeResponse(String challengeId) {}
