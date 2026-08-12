package com.barrier.webhook;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Roda todos os testes da webhook-api de uma vez (unidade + integração + arquitetura). Basta
 * executar esta classe pela IDE — o Docker precisa estar de pé por causa dos testes com
 * Testcontainers.
 *
 * <p>Testes de carga (tag {@code load}) ficam de fora, igual ao build do Maven.
 */
@Suite
@SuiteDisplayName("Webhook API — todos os testes")
@SelectPackages("com.barrier.webhook")
@IncludeClassNamePatterns(".*Test(s|Case)?$")
@ExcludeTags("load")
public class AllTestsSuite {}
