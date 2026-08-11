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
 * Trava do bureau de <b>CNPJ</b>, no padrão do {@link CpfBureauReadinessGuard}.
 *
 * <p>A cadeia de PJ falhava aberto: com a BrasilAPI fora do ar, a avaliação de pessoa jurídica caía
 * no provider simulado e virava verificação fictícia sem nada falhar. A trava fecha o caso em que
 * isso é permanente — subir em produção sem nenhum bureau real de PJ.
 *
 * <p>Além disso, <b>avisa</b> quando a BrasilAPI é o único bureau de CNPJ em produção. Ela é uma
 * API pública gratuita, sem SLA e sem contrato: usá-la é uma escolha legítima, mas é uma escolha —
 * um controle regulatório de PJ passa a depender de um serviço que ninguém se comprometeu a
 * manter no ar. O aviso existe para essa decisão ficar registrada em vez de acontecer por inércia.
 */
@Component
public class CnpjBureauReadinessGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(CnpjBureauReadinessGuard.class);
  private static final String PROD_PROFILE = "prod";
  private static final String CNPJ = "CNPJ";
  private static final String BRASILAPI = "brasilapi";

  private final List<BureauProvider> providers;
  private final Environment environment;

  public CnpjBureauReadinessGuard(List<BureauProvider> providers, Environment environment) {
    this.providers = providers;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean prod = Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
    List<BureauProvider> cnpjProviders = providers.stream().filter(p -> p.supports(CNPJ)).toList();
    List<BureauProvider> autoritativos =
        cnpjProviders.stream().filter(BureauProvider::authoritative).toList();

    String problema = problema(cnpjProviders, autoritativos);
    if (problema != null) {
      if (prod) {
        throw new IllegalStateException(
            "Profile 'prod' ativo e "
                + problema
                + ". Pessoa jurídica seria decidida sem verificação de identidade real. Habilite um "
                + "bureau real de CNPJ (barrier.identity.bigboost.enabled=true com credenciais e "
                + "base-url pública) antes de subir em produção.");
      }
      log.warn("KYC de pessoa jurídica SEM verificação real: {}. Não usar em produção.", problema);
      return;
    }
    if (prod && autoritativos.size() == 1 && BRASILAPI.equals(autoritativos.get(0).name())) {
      log.warn(
          "Único bureau de CNPJ em produção é a BrasilAPI: API pública gratuita, sem SLA e sem "
              + "contrato. O controle de PJ fica dependendo de um serviço que ninguém se "
              + "comprometeu a manter no ar — decisão consciente ou configuração esquecida?");
    }
  }

  private String problema(List<BureauProvider> cnpjProviders, List<BureauProvider> autoritativos) {
    if (autoritativos.isEmpty()) {
      return cnpjProviders.isEmpty()
          ? "nenhum provider de CNPJ habilitado"
          : "nenhum provider autoritativo de CNPJ: só há "
              + cnpjProviders.stream().map(BureauProvider::name).toList();
    }
    // Bureau "real" apontado para a própria máquina é um simulador com crachá — mesma brecha que o
    // guard de CPF fecha.
    String baseUrl = environment.getProperty("barrier.identity.bigboost.base-url", "");
    boolean soBigBoost = autoritativos.stream().noneMatch(p -> BRASILAPI.equals(p.name()));
    return soBigBoost && isLocal(baseUrl)
        ? "o bureau de CNPJ aponta para um endereço local (" + baseUrl + ")"
        : null;
  }

  private static boolean isLocal(String baseUrl) {
    String url = baseUrl == null ? "" : baseUrl.toLowerCase();
    return url.contains("localhost") || url.contains("127.0.0.1") || url.contains("://[::1]");
  }
}
