package com.barrier.riskengine.identity.domain;

import java.time.Instant;

/**
 * Procedência da verificação de identidade que sustentou uma avaliação — de onde ela veio e
 * quando de fato aconteceu.
 *
 * @param reused este check reaproveitou uma consulta anterior em vez de ir ao bureau
 * @param checkedAt instante da consulta que efetivamente foi à rede — segue {@code
 *     reusedFromId} quando {@code reused == true}, para não fingir que a verificação é de agora
 */
public record IdentityProvenance(boolean reused, Instant checkedAt) {}
