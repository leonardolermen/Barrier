package com.barrier.riskengine;

import com.barrier.riskengine.architecture.LayeredArchitectureTest;
import org.junit.platform.suite.api.ExcludeClassNamePatterns;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * Roda todos os testes da risk-engine de uma vez (unidade + integração com Testcontainers +
 * arquitetura). Basta executar esta classe pela IDE — o Docker precisa estar de pé por causa dos
 * testes com Testcontainers.
 *
 * <p>Testes de carga (tag {@code load}) ficam de fora, igual ao build do Maven.
 *
 * <p>O teste de arquitetura entra por {@code @SelectClasses}, não pela varredura de pacote: o
 * motor do ArchUnit é um TestEngine próprio e, ao descobrir por pacote, importa todo o
 * {@code target/classes} com um ASM que não lê bytecode Java 25 — falha na descoberta e o teste
 * some da suíte em silêncio. Selecionado pela classe, ele roda normalmente.
 *
 * <p>A exclusão por nome só tira esta classe, para a suíte não se selecionar em ciclo.
 */
@Suite
@SuiteDisplayName("Risk Engine — todos os testes")
@SelectPackages("com.barrier.riskengine")
@SelectClasses(LayeredArchitectureTest.class)
@ExcludeClassNamePatterns(".*AllTestsSuite$")
@ExcludeTags("load")
public class AllTestsSuite {}
