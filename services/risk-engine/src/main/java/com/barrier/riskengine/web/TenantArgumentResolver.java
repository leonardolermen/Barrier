package com.barrier.riskengine.web;

import com.barrier.riskengine.tenant.domain.AuthenticatedTenant;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injeta o {@link AuthenticatedTenant} nos controllers, a partir do que o
 * {@link TenantAuthenticationFilter} já validou.
 *
 * <p>O ganho não é ergonômico, é estrutural: o controller passa a <b>não ter como</b> obter o
 * tenant de outro lugar. Antes ele lia um header e chamava {@code TenantService.resolve} — um
 * endpoint novo que esquecesse essa dupla ficava aberto, e nada no código apontava o erro. Agora
 * o tenant só existe se a requisição passou pelo filtro.
 */
@Component
public class TenantArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return AuthenticatedTenant.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Object tenant =
        webRequest.getAttribute(
            TenantAuthenticationFilter.TENANT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (tenant == null) {
      // Só acontece se um endpoint declarar AuthenticatedTenant sem estar coberto pelo filtro:
      // é erro de configuração, e falhar alto é melhor que servir a requisição sem tenant.
      throw new IllegalStateException(
          "Rota declara AuthenticatedTenant mas não está coberta pelo TenantAuthenticationFilter");
    }
    return tenant;
  }
}
