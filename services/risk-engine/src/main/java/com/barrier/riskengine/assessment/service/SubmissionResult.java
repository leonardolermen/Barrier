package com.barrier.riskengine.assessment.service;

import com.barrier.riskengine.assessment.domain.Assessment;

/**
 * Desfecho de uma submissão.
 *
 * @param replayed a avaliação já existia e foi devolvida por causa da {@code Idempotency-Key} — o
 *     cliente precisa saber disso para não contar como submissão nova na conciliação dele
 */
public record SubmissionResult(Assessment assessment, boolean replayed) {}
