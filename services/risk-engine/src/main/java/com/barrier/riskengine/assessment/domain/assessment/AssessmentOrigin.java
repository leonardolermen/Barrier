package com.barrier.riskengine.assessment.domain.assessment;

/**
 * Por que esta avaliação existe.
 *
 * <p>Distinção de trilha, não de processamento: o pipeline é o mesmo nos dois casos, e é
 * deliberado que seja — rescreening que roda por um caminho paralelo decide diferente do
 * onboarding e ninguém percebe. O que muda é o que a origem permite responder depois: quantas
 * revisões vieram do monitoramento contínuo, e qual mudança de lista levantou cada uma.
 */
public enum AssessmentOrigin {

  /** Submetida pelo parceiro via {@code POST /v1/assessments}. */
  ONBOARDING,

  /** Criada pelo monitoramento contínuo, por uma entrada nova em lista restritiva. */
  RESCREENING,

  /** Criada por uma verificação de documentoscopia/biometria que pediu reavaliação. */
  ASSURANCE,

  /**
   * Criada por alteração material do cadastro (ADR-0019). {@code origin_detail} lista os campos
   * que mudaram — é o que permite ao analista ver a avaliação e saber que ela existe porque o
   * endereço mudou, não porque o cliente foi sancionado.
   */
  PROFILE_PATCH,

  /**
   * Reavaliação periódica de rotina (re-KYC), disparada pelo job diário quando o intervalo do nível
   * de risco do cliente vence. {@code origin_detail} registra o nível e o prazo aplicados — é o que
   * permite ao analista saber que a avaliação existe por rotina, não por fato novo.
   */
  PERIODIC_REVIEW
}
