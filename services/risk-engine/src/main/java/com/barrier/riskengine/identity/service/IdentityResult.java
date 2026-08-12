package com.barrier.riskengine.identity.service;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.IdentityCheck;
import com.barrier.riskengine.identity.domain.PersonProfile;

/**
 * Saída da verificação de identidade: o registro persistido mais o perfil objetivo devolvido pelo
 * bureau — de PJ ou de PF, conforme o documento.
 *
 * <p>O perfil trafega até o motor de risco e, desde que o cadastro passou a ser alimentado pelo
 * bureau, também até o {@code SubjectProfile}: os dados objetivos que a fonte já conhece não devem
 * ser cobrados do parceiro nem rebaixar a avaliação para revisão.
 *
 * @param check verificação persistida
 * @param company perfil da PJ; {@code null} para CPF, stubs ou bureau indisponível
 * @param person perfil da PF; {@code null} para CNPJ, stubs ou bureau indisponível
 */
public record IdentityResult(IdentityCheck check, CompanyProfile company, PersonProfile person) {

  public IdentityResult(IdentityCheck check, CompanyProfile company) {
    this(check, company, null);
  }
}
