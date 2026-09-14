package com.barrier.riskengine.riskpolicy.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Uma versão de política de risco de um tenant.
 *
 * <p>Chave natural: {@code (tenantId, domain, version)}. {@code version} é um inteiro monotônico
 * <b>por tenant</b>, não por domínio — assim {@code /v1/policies/{version}} identifica uma versão
 * sem precisar carregar o domínio na rota. {@code catalogVersion} é a versão do {@code
 * FieldCatalog} contra a qual as regras foram escritas, para que uma política antiga continue
 * interpretável quando o catálogo crescer.
 *
 * <p><b>Versão ativada é imutável.</b> Editar gera versão nova; o repositório não expõe método
 * que reescreva regra de versão já ativada — a ausência do método é a defesa, mesmo raciocínio de
 * {@code behavior_events}. {@link #activate} e {@link #archive} devolvem uma instância nova, nunca
 * mutam esta.
 *
 * @param rules regras já validadas pelo {@code PolicyCompiler} — uma {@code RiskPolicy} só existe
 *     depois de compilar
 * @param createdBy quem criou esta versão (DRAFT)
 * @param activatedBy quem ativou; {@code null} enquanto a versão não foi ativada
 * @param archivedAt quando esta versão foi arquivada; {@code null} enquanto ainda não foi
 */
public record RiskPolicy(
    UUID id,
    String tenantId,
    PolicyDomain domain,
    int version,
    PolicyStatus status,
    int catalogVersion,
    List<PolicyRule> rules,
    String createdBy,
    Instant createdAt,
    String activatedBy,
    Instant activatedAt,
    Instant archivedAt) {

  public RiskPolicy {
    rules = List.copyOf(rules);
  }

  /** Devolve uma nova instância {@code ACTIVE}; não muta esta. */
  public RiskPolicy activate(String by, Instant when) {
    return new RiskPolicy(
        id,
        tenantId,
        domain,
        version,
        PolicyStatus.ACTIVE,
        catalogVersion,
        rules,
        createdBy,
        createdAt,
        by,
        when,
        archivedAt);
  }

  /** Devolve uma nova instância {@code ARCHIVED}; não muta esta. */
  public RiskPolicy archive(Instant when) {
    return new RiskPolicy(
        id,
        tenantId,
        domain,
        version,
        PolicyStatus.ARCHIVED,
        catalogVersion,
        rules,
        createdBy,
        createdAt,
        activatedBy,
        activatedAt,
        when);
  }
}
