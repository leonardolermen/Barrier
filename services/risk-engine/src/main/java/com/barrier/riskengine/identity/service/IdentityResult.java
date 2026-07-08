package com.barrier.riskengine.identity.service;

import com.barrier.riskengine.identity.domain.CompanyProfile;
import com.barrier.riskengine.identity.domain.IdentityCheck;

/**
 * Saída da verificação de identidade: o registro persistido mais o perfil objetivo da PJ (quando
 * o bureau o fornece). O perfil é transiente — só trafega até o motor de risco, não é gravado.
 *
 * @param check verificação persistida
 * @param company perfil da PJ; {@code null} para CPF, stubs ou bureau indisponível
 */
public record IdentityResult(IdentityCheck check, CompanyProfile company) {}
