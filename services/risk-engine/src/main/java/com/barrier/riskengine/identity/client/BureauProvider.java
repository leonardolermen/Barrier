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

  /**
   * Indica se a resposta deste provider vale como verificação de identidade de verdade.
   *
   * <p>Existe para separar bureau de <b>stub</b>. O stub responde MATCH para qualquer documento
   * sintaticamente válido — é o que permite dev/teste rodarem sem provider pago. Como a cadeia de
   * {@code IdentityService} tem fallback, um bureau real fora do ar caía no stub e a
   * indisponibilidade virava <i>identidade verificada</i>: a falha mais perigosa possível, e
   * invisível (o {@code CpfBureauReadinessGuard} valida a configuração na subida, não o
   * comportamento em runtime).
   *
   * <p>Fallback só é resiliência entre fontes de confiabilidade equivalente. Entre um bureau e um
   * stub, fallback é fail-open.
   */
  default boolean authoritative() {
    return true;
  }
}
