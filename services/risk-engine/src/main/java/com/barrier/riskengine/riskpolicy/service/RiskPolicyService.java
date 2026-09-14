package com.barrier.riskengine.riskpolicy.service;

import com.barrier.riskengine.riskpolicy.domain.PolicyDomain;
import com.barrier.riskengine.riskpolicy.domain.PolicyRule;
import com.barrier.riskengine.riskpolicy.domain.PolicyStatus;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicy;
import com.barrier.riskengine.riskpolicy.domain.RiskPolicyNotFoundException;
import com.barrier.riskengine.riskpolicy.domain.catalog.FieldCatalog;
import com.barrier.riskengine.riskpolicy.repository.interfaces.RiskPolicyRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ciclo de vida de {@link RiskPolicy}: {@code DRAFT -> ACTIVE -> ARCHIVED}.
 *
 * <p>{@link #activate} é o método sensível: arquiva a {@code ACTIVE} atual do domínio (se houver) e
 * ativa a versão pedida <b>na mesma transação</b> — uma parada no meio não pode deixar o tenant sem
 * nenhuma política ativa nem com duas. A garantia de que só uma ACTIVE existe por (tenant, domínio)
 * não é só isto: é o índice único parcial em {@code risk_policies} (migration V049), que vale mesmo
 * quando duas réplicas tentam ativar ao mesmo tempo sem passar pela mesma instância deste serviço.
 */
@Service
public class RiskPolicyService {

  private final RiskPolicyRepository repository;
  private final PolicyCompiler compiler;

  public RiskPolicyService(RiskPolicyRepository repository, PolicyCompiler compiler) {
    this.repository = repository;
    this.compiler = compiler;
  }

  /**
   * Compila as regras (Task 4) antes de gravar -- uma {@code RiskPolicy} persistida já passou pelo
   * piso regulatório. {@code catalogVersion} grava a versão do {@link FieldCatalog} vigente agora,
   * para que a política continue interpretável quando o catálogo crescer.
   */
  @Transactional
  public RiskPolicy createDraft(
      String tenantId, PolicyDomain domain, List<PolicyRule> rules, String createdBy) {
    List<PolicyRule> compiladas = compiler.compile(rules);
    RiskPolicy draft =
        new RiskPolicy(
            UUID.randomUUID(),
            tenantId,
            domain,
            repository.nextVersion(tenantId),
            PolicyStatus.DRAFT,
            FieldCatalog.VERSION,
            compiladas,
            createdBy,
            Instant.now(),
            null,
            null,
            null);
    return repository.create(draft);
  }

  /** Lista as versões do tenant, na ordem em que foram criadas. */
  @Transactional(readOnly = true)
  public List<RiskPolicy> list(String tenantId) {
    return repository.listByTenant(tenantId);
  }

  /**
   * Lê uma versão do tenant. Escopado pelo mesmo {@code require} que {@link #activate} e
   * {@link #archive} usam: versão inexistente e versão de outro tenant são a mesma entrada para
   * quem chama, {@link RiskPolicyNotFoundException} -- 404, nunca 403.
   */
  @Transactional(readOnly = true)
  public RiskPolicy get(String tenantId, int version) {
    return require(tenantId, version);
  }

  @Transactional
  public RiskPolicy activate(String tenantId, int version, String activatedBy) {
    RiskPolicy policy = require(tenantId, version);
    if (policy.status() != PolicyStatus.DRAFT) {
      throw new PolicyStateException(
          "versão "
              + version
              + " do tenant '"
              + tenantId
              + "' não está em DRAFT (status atual: "
              + policy.status()
              + ") -- só uma versão DRAFT pode ser ativada");
    }
    Instant when = Instant.now();
    repository
        .findActive(tenantId, policy.domain())
        .ifPresent(atual -> repository.archive(atual.id(), when));
    repository.activate(policy.id(), activatedBy, when);
    return policy.activate(activatedBy, when);
  }

  /**
   * Arquivar uma versão já {@code ARCHIVED} é recusado, não um no-op silencioso: sobrescreveria
   * {@code archivedAt} com um instante novo e destruiria a resposta para "quando esta política
   * deixou de valer" -- a mesma trilha exata que motivou {@code config_history} e a imutabilidade
   * da versão ativada. Um retry de rede depois de timeout faz exatamente essa segunda chamada, e
   * um 409 avisando que já estava arquivada é melhor que um 200 que esconde o bug do cliente.
   */
  @Transactional
  public RiskPolicy archive(String tenantId, int version) {
    RiskPolicy policy = require(tenantId, version);
    if (policy.status() == PolicyStatus.ARCHIVED) {
      throw new PolicyStateException(
          "versão "
              + version
              + " do tenant '"
              + tenantId
              + "' já está ARCHIVED -- arquivar de novo destruiria o archivedAt original");
    }
    Instant when = Instant.now();
    repository.archive(policy.id(), when);
    return policy.archive(when);
  }

  /**
   * Resolve {@code (tenantId, version)} contra o repositório -- caminho que {@link #activate} e
   * {@link #archive} percorrem primeiro, antes de qualquer verificação de estado. Antes lançava
   * {@code NoSuchElementException} cru, que o {@code ProblemExceptionHandler} não mapeia: uma
   * versão inexistente (o caso normal de um retry ou de um id digitado errado) virava 500 em vez
   * de 404.
   */
  private RiskPolicy require(String tenantId, int version) {
    return repository
        .findByTenantAndVersion(tenantId, version)
        .orElseThrow(() -> new RiskPolicyNotFoundException(tenantId, version));
  }
}
