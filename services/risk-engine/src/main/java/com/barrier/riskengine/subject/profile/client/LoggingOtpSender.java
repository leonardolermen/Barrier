package com.barrier.riskengine.subject.profile.client;

import com.barrier.riskengine.subject.profile.client.interfaces.OtpSender;
import com.barrier.riskengine.subject.profile.domain.VerifiableField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Envio simulado para desenvolvimento: registra o código no log em vez de mandar SMS/e-mail.
 *
 * <p>{@code @Profile("!prod")} não é cosmético. Sem ele, subir em produção sem provedor de SMS
 * contratado deixaria o fluxo "funcionando" — o desafio seria emitido, o código apareceria no log,
 * e a verificação passaria a atestar posse de um canal que ninguém confirmou. É o mesmo erro do
 * bureau simulado servindo de fallback: mock com crachá é pior que ausência, porque a ausência
 * aparece.
 */
@Component
@Profile("!prod")
public class LoggingOtpSender implements OtpSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

  @Override
  public void send(VerifiableField field, String target, String code) {
    log.info("OTP simulado para {} ({}): {}", field, masked(target), code);
  }

  /** Nem em dev o destino sai inteiro no log: telefone e e-mail são dado pessoal. */
  private static String masked(String target) {
    if (target == null || target.length() <= 4) {
      return "****";
    }
    return "****" + target.substring(target.length() - 4);
  }

  @Override
  public String name() {
    return "otp-simulado";
  }
}
