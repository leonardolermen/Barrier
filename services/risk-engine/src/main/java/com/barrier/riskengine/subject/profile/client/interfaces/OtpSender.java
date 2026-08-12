package com.barrier.riskengine.subject.profile.client.interfaces;

import com.barrier.riskengine.subject.profile.domain.VerifiableField;

/**
 * Envio do código de uso único ao canal declarado (SMS/e-mail).
 *
 * <p>Interface porque é integração externa, como bureau e watchlist: trocar de provedor de SMS não
 * pode encostar na regra de verificação.
 */
public interface OtpSender {

  /**
   * @param target número ou e-mail declarado — o código vai para o canal <b>declarado</b>, que é o
   *     que torna a devolução dele uma prova de posse
   */
  void send(VerifiableField field, String target, String code);

  String name();
}
