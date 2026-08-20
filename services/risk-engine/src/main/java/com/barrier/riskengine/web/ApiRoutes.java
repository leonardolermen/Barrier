package com.barrier.riskengine.web;

import java.util.regex.Pattern;

/**
 * Classificação das rotas da API entre <b>administração</b> e <b>negócio</b> — a fonte única que os
 * dois filtros consultam, para que não exista rota sem dono.
 *
 * <p>A regra é uma <b>denylist</b>, e essa inversão é o ponto: tudo sob {@code /v1/} exige tenant,
 * <i>exceto</i> o que estiver declarado como administrativo. Antes era o contrário — o filtro de
 * tenant tinha uma allowlist ({@code assessments|subjects}) e os módulos {@code mesa} e
 * {@code behavior} nasceram fora dela. Não ficaram só sem autenticação: como o
 * {@link TenantArgumentResolver} falha alto quando não há tenant na requisição, os dois ficaram
 * <b>inacessíveis para qualquer chamador</b>, e duas entregas inteiras não funcionaram sem que
 * nenhum teste apontasse.
 *
 * <p>O {@code TenantArgumentResolver} tornou impossível <i>servir uma requisição sem tenant</i>;
 * não tornava impossível <i>esquecer de registrar a rota</i>. Com a denylist, endpoint novo nasce
 * protegido e o esquecimento passa a falhar do lado seguro: 401 em rota que deveria ser admin é
 * visível na hora, ao contrário de rota de negócio servida sem credencial.
 *
 * <p>{@code ApiRouteCoverageTest} enumera os controllers e exige que cada um caia em exatamente um
 * dos dois lados.
 */
final class ApiRoutes {

  /** Prefixo da API de negócio versionada. Rota fora daqui não é coberta por filtro nenhum. */
  private static final String API_PREFIX = "/v1/";

  /**
   * {@code /v1/risk-rules[/...]}, {@code /v1/tenants/{id}/risk-config} e
   * {@code /v1/tenants/{id}/api-keys}. São endpoints que mudam <b>como o motor decide</b> (para
   * todos os tenants ou para um parceiro) e a emissão de credencial — se esta fosse self-service,
   * qualquer um emitiria a chave de qualquer tenant e a autenticação não valeria nada.
   *
   * <p>Administração resolve o tenant pelo <b>path</b>, de propósito: o admin opera sobre outros
   * tenants. Por isso não passa pelo filtro de tenant.
   */
  private static final Pattern ADMIN =
      Pattern.compile("^/v1/(risk-rules(/.*)?|tenants/[^/]+/(risk-config|api-keys))$");

  private ApiRoutes() {}

  /** Rota administrativa: autenticada pelo {@link AdminApiKeyFilter} ("é o operador do Barrier?"). */
  static boolean isAdmin(String uri) {
    return ADMIN.matcher(uri).matches();
  }

  /**
   * Rota de negócio: autenticada pelo {@link TenantAuthenticationFilter} ("é qual cliente?"). Tudo
   * sob {@code /v1/} que não seja administração — inclusive rota que ainda não existe.
   */
  static boolean isTenantScoped(String uri) {
    return uri.startsWith(API_PREFIX) && !isAdmin(uri);
  }
}
