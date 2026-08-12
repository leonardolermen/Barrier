package com.barrier.riskengine;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.IncludeClassNamePatterns;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Roda todos os testes da risk-engine de uma vez (unidade + integração com Testcontainers +
 * arquitetura). Basta executar esta classe pela IDE — o Docker precisa estar de pé por causa dos
 * testes com Testcontainers.
 *
 * <p>Testes de carga (tag {@code load}) ficam de fora, igual ao build do Maven.
 */
@Suite
@SuiteDisplayName("Risk Engine — todos os testes")
@SelectPackages("com.barrier.riskengine")
@IncludeClassNamePatterns(".*Test(s|Case)?$")
@ExcludeTags("load")
public class AllTestsSuite {}
