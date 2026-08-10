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
 * <p>O {@link StubBureauProvider} responde MATCH para <b>qualquer</b> CPF sintaticamente válido —
 * ele existe para dev/testes rodarem sem provider pago. Como é o último da cadeia
 * ({@code @Order(100)}), ele entra em ação sempre que nenhum bureau real de CPF está habilitado, e
 * o efeito é que toda pessoa física é aprovada com identidade "verificada" sem verificação alguma.
 * Sem esta trava a falha é invisível: a aplicação sobe saudável e o {@code /actuator/health} fica
 * verde.
 *
 * <p>Falha rápido (não sobe) se o profile {@code prod} estiver ativo e o stub for o único provider
 * de CPF. Em outros profiles, apenas avisa.
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
    List<BureauProvider> cpfProviders = providers.stream().filter(p -> p.supports(CPF)).toList();
    // Autoritativo = bureau de verdade. Comparar por nome com o stub deixava passar qualquer outro
    // provider não-autoritativo que viesse a existir, e falhava se houvesse dois deles.
    boolean hasAuthoritative = cpfProviders.stream().anyMatch(BureauProvider::authoritative);
    if (hasAuthoritative) {
      return;
    }

    String problema =
        cpfProviders.isEmpty()
            ? "nenhum provider de CPF habilitado"
            : "nenhum provider autoritativo de CPF: só há "
                + cpfProviders.stream().map(BureauProvider::name).toList()
                + ", que confirmam qualquer documento";
    if (Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
      throw new IllegalStateException(
          "Profile 'prod' ativo e "
              + problema
              + ". Toda pessoa física seria aprovada com identidade não verificada. Habilite um "
              + "bureau real de CPF (ex.: barrier.identity.bigboost.enabled=true com credenciais) "
              + "antes de subir em produção.");
    }
    log.warn("KYC de pessoa física SEM verificação real: {}. Não usar em produção.", problema);
  }
}
