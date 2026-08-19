package com.barrier.riskengine.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Nenhuma rota de negócio pode existir sem estar coberta por um dos dois filtros.
 *
 * <p><b>Este é o teste que faltava.</b> O bug que o motivou não foi um caso não testado: foi uma
 * <i>categoria</i> não testada. {@code /v1/mesa/**} e {@code /v1/behavior-events} nasceram fora da
 * allowlist do {@link TenantAuthenticationFilter} e ficaram inacessíveis, e nada no build apontou —
 * porque cada módulo testava o próprio domínio e ninguém testava a fronteira HTTP como conjunto.
 *
 * <p>Testar "mesa exige credencial" corrigiria o caso e deixaria o próximo módulo repetir o erro.
 * O que fecha a classe inteira é enumerar os controllers e exigir classificação de <b>todos</b>.
 */
class ApiRouteCoverageTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.barrier.riskengine");

  /**
   * Guard contra passagem vacua, no mesmo espírito do {@code LayeredArchitectureTest}: se o
   * import parar de enxergar controllers, todas as asserções abaixo passariam sobre lista vazia.
   */
  @Test
  void encontraOsControllersDoModulo() {
    assertThat(basePaths()).as("nenhum controller importado — o teste passaria vacuamente").hasSizeGreaterThan(8);
  }

  @Test
  void todaRotaDeNegocioViveSobV1() {
    assertThat(basePaths())
        .as(
            "rota fora de /v1 não é coberta por filtro nenhum: a proteção é por prefixo, "
                + "então sair do prefixo é sair da autenticação")
        .allSatisfy(path -> assertThat(path).startsWith("/v1/"));
  }

  @Test
  void todaRotaEhClassificadaComoTenantOuAdmin() {
    for (String path : basePaths()) {
      String probe = concreteProbe(path);
      assertThat(ApiRoutes.isTenantScoped(probe) || ApiRoutes.isAdmin(probe))
          .as("rota %s não é coberta pelo filtro de tenant nem pelo de admin", path)
          .isTrue();
    }
  }

  /** Um filtro só: cobertura dupla significaria exigir tenant num endpoint administrativo. */
  @Test
  void nenhumaRotaEhCobertaPelosDoisFiltros() {
    for (String path : basePaths()) {
      String probe = concreteProbe(path);
      assertThat(ApiRoutes.isTenantScoped(probe) && ApiRoutes.isAdmin(probe))
          .as("rota %s cai nos dois filtros", path)
          .isFalse();
    }
  }

  /**
   * Administração é a exceção, e exceção se declara explicitamente. Se um controller novo entrar
   * em {@code /v1/tenants/...} ou {@code /v1/risk-rules}, esta lista precisa ser revista de
   * propósito, não por acidente.
   */
  @Test
  void apenasOsControllersAdministrativosConhecidosSaoExcecao() {
    List<String> admin =
        basePaths().stream().filter(path -> ApiRoutes.isAdmin(concreteProbe(path))).sorted().toList();

    assertThat(admin)
        .containsExactly(
            "/v1/risk-rules", "/v1/tenants/{tenantId}/api-keys", "/v1/tenants/{tenantId}/risk-config");
  }

  /** Substitui variáveis de path por um valor concreto, para casar contra os regexes dos filtros. */
  private static String concreteProbe(String basePath) {
    return basePath.replaceAll("\\{[^/}]+}", "x");
  }

  private static List<String> basePaths() {
    List<String> paths = new ArrayList<>();
    for (JavaClass type : CLASSES) {
      if (!type.getSimpleName().endsWith("Controller")) {
        continue;
      }
      mappingValue(type).ifPresent(paths::add);
    }
    return paths;
  }

  private static Optional<String> mappingValue(JavaClass type) {
    for (JavaAnnotation<JavaClass> annotation : type.getAnnotations()) {
      if (!annotation.getRawType().getName().endsWith("RequestMapping")) {
        continue;
      }
      Object value = annotation.getProperties().get("value");
      if (value instanceof String[] values && values.length > 0) {
        return Optional.of(values[0]);
      }
    }
    return Optional.empty();
  }
}
