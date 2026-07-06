package com.barrier.riskengine.identity.client;

/**
 * Integração com um bureau de identidade (Serpro/Receita, Serasa, ...), atrás de interface.
 * O {@code service} depende disto, nunca de um SDK concreto.
 */
public interface BureauProvider {

  /** Indica se este provider atende o tipo de documento informado ("CPF"/"CNPJ"). */
  boolean supports(String documentType);

  /**
   * Consulta o bureau.
   *
   * @throws BureauUnavailableException se o bureau estiver indisponível
   */
  BureauResult check(BureauQuery query);

  /** Nome curto do bureau, para auditoria. */
  String name();
}
