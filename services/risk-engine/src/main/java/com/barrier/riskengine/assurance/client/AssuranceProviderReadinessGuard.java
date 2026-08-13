package com.barrier.riskengine.assurance.client;

import com.barrier.riskengine.assurance.client.interfaces.BiometricVerificationProvider;
import com.barrier.riskengine.assurance.client.interfaces.DocumentVerificationProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Em produção, recusa subir com documentoscopia ou biometria simuladas.
 *
 * <p>Mesmo padrão de {@code CpfBureauReadinessGuard}/{@code CnpjBureauReadinessGuard}, e pelo mesmo
 * motivo: um simulador com crachá é pior que a ausência do controle, porque a ausência aparece.
 * Aqui seria pior ainda — o simulado aprova a prova de vida de qualquer captura, e o sistema
 * passaria a afirmar que verificou a presença do titular sem ter verificado nada.
 */
@Component
@Profile("prod")
public class AssuranceProviderReadinessGuard implements ApplicationRunner {

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
    reject(documentProvider.name(), "documentoscopia");
    reject(biometricProvider.name(), "biometria");
  }

  private static void reject(String providerName, String what) {
    if (providerName.endsWith("-simulada")) {
      throw new IllegalStateException(
          "Provedor de "
              + what
              + " simulado ativo em produção ("
              + providerName
              + "): a aplicação afirmaria ter verificado o titular sem ter verificado nada."
              + " Contrate um provedor real ou desabilite a exigência de verificação.");
    }
  }
}
