package com.barrier.riskengine.tenant.service;

import com.barrier.riskengine.tenant.domain.ApiKeyMaterial;
import com.barrier.riskengine.tenant.repository.ApiKeyRepository;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Emite uma credencial de desenvolvimento na subida, quando nenhuma existe, e a imprime no log —
 * mesmo padrão da senha gerada pelo Spring Boot Security.
 *
 * <p>Existe porque a migration <b>não</b> semeia chave nenhuma: uma credencial conhecida versionada
 * no repositório é exatamente o problema do {@code dev-secret} do webhook. Sem isto, subir em dev
 * exigiria emitir a chave por fora antes de qualquer chamada.
 *
 * <p>Nunca roda em {@code prod}: lá a credencial sai por {@code POST /v1/tenants/{id}/api-keys},
 * protegido pelo gate de admin, e não vai para log.
 */
@Component
public class DevApiKeyIssuer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DevApiKeyIssuer.class);
  private static final String PROD_PROFILE = "prod";

  private final ApiKeyService apiKeyService;
  private final ApiKeyRepository apiKeys;
  private final Environment environment;
  private final String tenantId;

  public DevApiKeyIssuer(
      ApiKeyService apiKeyService,
      ApiKeyRepository apiKeys,
      Environment environment,
      @Value("${barrier.auth.dev-key-tenant:default}") String tenantId) {
    this.apiKeyService = apiKeyService;
    this.apiKeys = apiKeys;
    this.environment = environment;
    this.tenantId = tenantId;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
      return;
    }
    if (apiKeys.countActive() > 0) {
      return;
    }
    ApiKeyMaterial.Generated generated = apiKeyService.issue(tenantId, "dev");
    log.warn(
        """

        ================================================================
        API key de DESENVOLVIMENTO emitida para o tenant '{}':

            {}

        Use em: Authorization: Bearer <a chave acima>
        Não existe em produção — lá a chave sai pelo endpoint de admin.
        ================================================================""",
        tenantId,
        generated.presentedValue());
  }
}
