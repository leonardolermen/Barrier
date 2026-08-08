package com.barrier.riskengine.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registra o resolver que injeta o tenant autenticado nos controllers. */
@Configuration
class WebConfig implements WebMvcConfigurer {

  private final TenantArgumentResolver tenantArgumentResolver;

  WebConfig(TenantArgumentResolver tenantArgumentResolver) {
    this.tenantArgumentResolver = tenantArgumentResolver;
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(tenantArgumentResolver);
  }
}
