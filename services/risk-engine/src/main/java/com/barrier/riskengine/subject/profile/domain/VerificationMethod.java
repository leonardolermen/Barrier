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
  ADDRESS_LOOKUP
}
