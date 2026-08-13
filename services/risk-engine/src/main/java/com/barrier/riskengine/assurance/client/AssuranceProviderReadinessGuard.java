package com.barrier.riskengine.assurance.client;

import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Em produção, <b>avisa</b> quando documentoscopia ou biometria seguem sem provedor real
 * contratado — no padrão de {@link com.barrier.riskengine.identity.client.CnpjBureauReadinessGuard},
 * que avisa em vez de derrubar quando a única fonte de PJ é insuficiente.
 *
 * <p><b>Não lança mais.</b> Até a revisão final desta frente, este guard tentava recusar a subida
 * quando via um provider {@code -simulada} — mas ele próprio injeta {@code
 * DocumentVerificationProvider}/{@code BiometricVerificationProvider} por construtor obrigatório,
 * e em produção {@code StubDocumentVerificationProvider}/{@code StubBiometricVerificationProvider}
 * são {@code @Profile("!prod")}: não existe bean nenhum para injetar, e o contexto falhava com
 * {@code UnsatisfiedDependencyException} — pior que a mensagem que este guard tentava dar, porque
 * derrubava a aplicação <b>inteira</b>, não só a submissão de assurance. A correção estrutural foi
 * dar a produção um provedor próprio ({@link UnavailableDocumentVerificationProvider}/{@link
 * UnavailableBiometricVerificationProvider}, que devolvem {@code UNAVAILABLE}); o papel deste
 * guard passou a ser só constatar que aquele provedor de emergência é quem está ativo, e avisar —
 * documentoscopia/biometria simuladas nunca chegam a existir como bean em {@code prod}, então não
 * há mais nada aqui para barrar na subida.
 */
@Component
@Profile("prod")
public class AssuranceProviderReadinessGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AssuranceProviderReadinessGuard.class);
  private static final String SUFFIX = "-indisponivel";

  private final DocumentVerificationProvider documentProvider;
  private final BiometricVerificationProvider biometricProvider;

  public AssuranceProviderReadinessGuard(
      DocumentVerificationProvider documentProvider,
      BiometricVerificationProvider biometricProvider) {
    this.documentProvider = documentProvider;
    this.biometricProvider = biometricProvider;
  }

  @Override
  public void run(ApplicationArguments args) {
    warnIfUnavailable(documentProvider.name(), "documentoscopia");
    warnIfUnavailable(biometricProvider.name(), "biometria");
  }

  private static void warnIfUnavailable(String providerName, String what) {
    if (providerName.endsWith(SUFFIX)) {
      log.warn(
          "Nenhum provedor real de {} contratado em produção (provider={}): toda submissão "
              + "devolve UNAVAILABLE — IdentityAssuranceRiskRule trata isso como inconclusivo, "
              + "nunca como titularidade verificada. Contrate um provedor real antes de exigir "
              + "esta etapa do fluxo de onboarding.",
          what,
          providerName);
    }
  }
}
