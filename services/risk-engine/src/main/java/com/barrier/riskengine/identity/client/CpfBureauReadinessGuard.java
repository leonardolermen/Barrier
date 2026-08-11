package com.barrier.riskengine.identity.client;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Trava de segurança operacional do bureau de CPF, no mesmo espírito do
 * {@code WatchlistReadinessGuard}.
 *
 * <p>O {@code FakeCpfBureauProvider} devolve desfechos simulados derivados do próprio CPF — existe
 * para dev/testes rodarem sem provider pago. Sem esta trava, subir em produção sem bureau real
 * significaria decidir sobre pessoa física com resposta inventada, e a falha seria invisível: a
 * aplicação sobe saudável e o {@code /actuator/health} fica verde.
 *
 * <p>Duas condições fazem a subida falhar em {@code prod}:
 *
 * <ol>
 *   <li>nenhum provider <b>autoritativo</b> de CPF na cadeia;
 *   <li>bureau real habilitado, mas apontando para {@code localhost} — configuração de
 *       desenvolvimento copiada para produção. Sem esta segunda checagem, apontar a
 *       {@code base-url} para um mock local tornaria o provider autoritativo e desarmaria a
 *       primeira, que é o buraco por onde a trava seria contornada sem ninguém decidir isso.
 * </ol>
 *
 * <p>Em outros profiles, apenas avisa.
 */
@Component
public class CpfBureauReadinessGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(CpfBureauReadinessGuard.class);
  private static final String PROD_PROFILE = "prod";
  private static final String CPF = "CPF";

  private final List<BureauProvider> providers;
  private final Environment environment;

  public CpfBureauReadinessGuard(List<BureauProvider> providers, Environment environment) {
    this.providers = providers;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    String problema = problema();
    if (problema == null) {
      return;
    }
    if (Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
      throw new IllegalStateException(
          "Profile 'prod' ativo e "
              + problema
              + ". Pessoa física seria decidida sem verificação de identidade real. Habilite um "
              + "bureau real de CPF (ex.: barrier.identity.bigboost.enabled=true com credenciais e "
              + "base-url pública) antes de subir em produção.");
    }
    log.warn("KYC de pessoa física SEM verificação real: {}. Não usar em produção.", problema);
  }

  /** Descrição do que impede confiar no KYC de PF; {@code null} quando está tudo certo. */
  private String problema() {
    List<BureauProvider> cpfProviders = providers.stream().filter(p -> p.supports(CPF)).toList();
    // Autoritativo = bureau de verdade. Comparar por nome com uma classe específica deixava passar
    // qualquer outro provider não-autoritativo que viesse a existir.
    if (cpfProviders.stream().noneMatch(BureauProvider::authoritative)) {
      return cpfProviders.isEmpty()
          ? "nenhum provider de CPF habilitado"
          : "nenhum provider autoritativo de CPF: só há "
              + cpfProviders.stream().map(BureauProvider::name).toList();
    }
    String baseUrl = environment.getProperty("barrier.identity.bigboost.base-url", "");
    return isLocal(baseUrl)
        ? "o bureau de CPF aponta para um endereço local (" + baseUrl + ")"
        : null;
  }

  /**
   * Um bureau "real" apontado para a própria máquina é um simulador com crachá: torna o provider
   * autoritativo e desarma a checagem acima sem que ninguém tenha decidido isso.
   */
  private static boolean isLocal(String baseUrl) {
    String url = baseUrl == null ? "" : baseUrl.toLowerCase();
    return url.contains("localhost") || url.contains("127.0.0.1") || url.contains("://[::1]");
  }
}
