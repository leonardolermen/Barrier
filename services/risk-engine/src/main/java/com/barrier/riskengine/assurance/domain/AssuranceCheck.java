package com.barrier.riskengine.assurance.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
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
 * @param detail mensagem humana do provedor sobre o desfecho — só isso. Divergência de
 *     nome/nascimento contra o cadastro vive em {@link #divergences}, não aqui: {@code detail}
 *     é texto livre do provedor, sem limite conhecido, e concatenar um marcador nele arriscava
 *     truncar contra o {@code VARCHAR(400)} da coluna (ou pior, estourar e derrubar a transação
 *     inteira) — perdendo justamente a verificação que capturou a divergência
 * @param divergences campos lidos do documento que divergem do declarado (V037). Nunca carrega o
 *     valor declarado nem o extraído — só quais campos divergiram; a evidência vai para a trilha
 *     de auditoria, e CPF/CNPJ/nome não podem aparecer lá sem máscara
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
    Set<DivergentField> divergences,
    Instant checkedAt,
    AssuranceConsent consent) {

  public AssuranceCheck {
    divergences =
        divergences == null || divergences.isEmpty()
            ? Set.of()
            : Set.copyOf(EnumSet.copyOf(divergences));
  }

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
        divergences,
        checkedAt,
        consent);
  }

  /**
   * Devolve uma cópia com os campos divergentes anexados. Mesmo padrão de {@link #withConsent}:
   * quem compara o extraído contra o cadastro é o serviço, não o provedor, e um campo novo em
   * {@code AssuranceCheck} não deveria obrigar a reescrever o construtor de 14 argumentos em
   * todo call-site.
   */
  public AssuranceCheck withDivergences(Set<DivergentField> divergences) {
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
        divergences,
        checkedAt,
        consent);
  }
}
