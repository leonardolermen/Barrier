package com.barrier.riskengine.riskpolicy.domain;

import java.util.UUID;

/**
 * Lançada quando uma versão de {@link RiskPolicy} não é encontrada.
 *
 * <p>Substitui {@code NoSuchElementException} cru em {@code RiskPolicyRepositoryImpl.activate}/
 * {@code .archive} e em {@code RiskPolicyService.require} -- mesma razão de {@code
 * PolicyStateException} não ser {@code IllegalStateException}: o tipo é o que separa um conflito
 * de domínio, que o {@code ProblemExceptionHandler} mapeia para 404, de um erro de programação
 * genérico da plataforma.
 *
 * <p>Duas formas: por {@code id} (usada pelo repositório, que já resolveu a linha para chegar
 * até ali) e por {@code (tenantId, version)} (usada por {@code RiskPolicyService.require}, o
 * caminho que um caller de verdade percorre -- {@code activate}/{@code archive}/{@code get}
 * nunca têm o {@code id} de antemão, só a chave natural que o parceiro enviou). Versão de outro
 * tenant não resolve pela mesma query filtrada por {@code tenantId}, então esta exceção também
 * é o que faz "não existe" e "existe, mas é de outro tenant" responderem igual -- 404 e nunca
 * 403, que confirmaria a existência.
 */
public class RiskPolicyNotFoundException extends RuntimeException {

  public RiskPolicyNotFoundException(UUID id) {
    super("Política de risco não encontrada: " + id);
  }

  public RiskPolicyNotFoundException(String tenantId, int version) {
    super("Política de risco não encontrada: tenant '" + tenantId + "', versão " + version);
  }
}
