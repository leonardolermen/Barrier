package com.barrier.riskengine.identity.client.serpro;

import com.barrier.riskengine.identity.client.BureauProvider;
import com.barrier.riskengine.identity.client.BureauQuery;
import com.barrier.riskengine.identity.client.BureauResult;
import com.barrier.riskengine.identity.client.BureauUnavailableException;

/**
 * Esqueleto da integração real com o Serpro (CPF/CNPJ na Receita Federal).
 *
 * <p>Ainda NÃO é um bean Spring (sem {@code @Component}) — não é ativado. Quando a integração
 * for implementada, torná-lo condicional (ex.: {@code barrier.identity.provider=serpro}) e
 * substituir o {@code FakeCpfBureauProvider} como provider ativo.
 */
public class SerproBureauProvider implements BureauProvider {

  @Override
  public boolean supports(String documentType) {
    return "CPF".equals(documentType) || "CNPJ".equals(documentType);
  }

  @Override
  public BureauResult check(BureauQuery query) {
    // TODO(fase real): chamar a API do Serpro, mapear timeout/5xx para
    // BureauUnavailableException e a resposta para MATCH/NOT_FOUND/MISMATCH.
    throw new BureauUnavailableException("integração Serpro ainda não implementada");
  }

  @Override
  public String name() {
    return "serpro";
  }
}
