package com.barrier.riskengine.tenant.domain;

/**
 * Tenant autenticado por credencial, e a chave que o autenticou.
 *
 * <p>Guardar qual chave agiu importa para a trilha: {@code reviewedBy} continua sendo um texto que
 * o chamador escreve, então não prova <i>quem</i> decidiu. Saber a credencial pelo menos limita a
 * atribuição a um portador conhecido e permite revogar. Identidade por operador humano depende de
 * autenticação de usuário (OIDC/SSO), que ainda não existe.
 *
 * @param tenant tenant dono da credencial
 * @param keyName rótulo da chave usada
 */
public record AuthenticatedTenant(Tenant tenant, String keyName) {

  public String id() {
    return tenant.id();
  }
}
