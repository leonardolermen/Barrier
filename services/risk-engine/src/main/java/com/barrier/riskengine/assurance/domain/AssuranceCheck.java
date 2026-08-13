package com.barrier.riskengine.assurance.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Resultado de uma verificação de documentoscopia ou biometria.
 *
 * <p><b>Não existe campo de imagem aqui, e não é omissão</b> (ADR-0016): foto do documento, selfie
 * e template biométrico não são armazenados. O que se guarda é o desfecho e o ponteiro para a
 * consulta no provedor, que mantém a evidência sob o controle de acesso dele — mesmo padrão do
 * rastro de bureau (V031).
 *
 * @param score confiança 0..100 quando o provedor a fornece; {@code null} quando só há desfecho
 * @param algorithmVersion versão do modelo do provedor. Sem ela, um score de hoje e um de seis
 *     meses atrás são réguas diferentes comparadas como se fossem a mesma
 * @param submittedHash SHA-256 do que foi submetido. Não reconstrói rosto nem documento, e é o que
 *     permite provar depois que a imagem apresentada numa contestação é a que foi analisada
 * @param consent prova de consentimento do titular para esta verificação, nesta finalidade.
 *     Anexado pelo serviço, não pelo provedor: consentimento é obrigação legal do tratamento,
 *     não parte da verificação técnica de documento/biometria
 */
public record AssuranceCheck(
    UUID id,
    UUID subjectId,
    String tenantId,
    AssuranceKind kind,
    AssuranceOutcome outcome,
    Integer score,
    String provider,
    String providerReference,
    String algorithmVersion,
    String submittedHash,
    String detail,
    Instant checkedAt,
    AssuranceConsent consent) {

  /**
   * Marcador gravado em {@link #detail} quando nome, documento ou nascimento lidos da
   * documentoscopia divergem do que o cadastro (CMN 4.753) ou o {@code Subject} declaram.
   *
   * <p>Vive aqui, em vez de em {@code AssuranceSummary}/{@code RiskContext}, porque este é o
   * único lugar que {@code AssuranceService} (módulo {@code assurance}) e
   * {@code IdentityAssuranceRiskRule} (módulo {@code risk}) já compartilham sem violar a regra de
   * camadas do ArchUnit — {@code risk.rule} não pode depender de uma classe do pacote
   * {@code service} de outro módulo, só de {@code domain}.
   */
  public static final String CADASTRO_DIVERGENCE_MARKER = "cadastro-divergente";

  /** Verificação que sustenta aprovação automática: só o desfecho positivo serve. */
  public boolean passed() {
    return outcome == AssuranceOutcome.PASS;
  }

  /**
   * Indisponibilidade do provedor não é falha do cliente — quem decide o que fazer com ela é o
   * motor de risco, como já faz com bureau indisponível.
   */
  public boolean inconclusive() {
    return outcome == AssuranceOutcome.INCONCLUSIVE || outcome == AssuranceOutcome.UNAVAILABLE;
  }

  /**
   * Devolve uma cópia com o consentimento anexado. O provedor devolve o {@code AssuranceCheck}
   * sem saber de consentimento; é o serviço que carimba antes de persistir.
   */
  public AssuranceCheck withConsent(AssuranceConsent consent) {
    return new AssuranceCheck(
        id,
        subjectId,
        tenantId,
        kind,
        outcome,
        score,
        provider,
        providerReference,
        algorithmVersion,
        submittedHash,
        detail,
        checkedAt,
        consent);
  }
}
