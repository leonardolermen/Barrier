package com.barrier.riskengine.subject.profile.domain;

/**
 * Como o campo foi verificado. Fica na trilha porque os meios não têm a mesma força: um OTP prova
 * posse do canal no instante do envio, o bureau prova concordância com um registro oficial, e
 * tratar os dois como "verificado" indistinto apagaria a diferença justamente numa contestação.
 */
public enum VerificationMethod {

  /** Código de uso único enviado ao canal declarado e devolvido pelo cliente. */
  OTP,

  /** Valor declarado bate com o que a fonte autoritativa devolveu para o documento. */
  BUREAU,

  /** Valor conferido contra base de endereçamento. */
  ADDRESS_LOOKUP,

  /** Valor declarado bate com o que a documentoscopia leu do documento apresentado. */
  DOCUMENT,

  /**
   * Valor declarado bate com o que a Receita Federal (ou, para endereço, a base da CNH via
   * SENATRAN) devolveu na validação cadastral do Datavalid/Serpro ({@code pessoa-fisica/
   * validacao}). Distinto de {@code BUREAU}: a fonte é outra (RFB/SENATRAN, não o bureau
   * comercial) e a força de prova numa contestação precisa continuar rastreável até a fonte
   * exata — apagar a diferença seria o mesmo erro que motivou separar OTP de BUREAU.
   */
  REGISTRY
}
