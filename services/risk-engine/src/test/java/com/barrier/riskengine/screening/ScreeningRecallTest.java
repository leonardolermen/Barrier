package com.barrier.riskengine.screening;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.commons.name.NameTokens;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Recall e precisão do match por nome contra um conjunto rotulado.
 *
 * <p><b>Por que existe.</b> O limiar de 0.90 foi escolhido por raciocínio, não por curva. Sem
 * conjunto rotulado, qualquer mudança no caminho de comparação — e a próxima é grande, o índice
 * que vai substituir o {@code findAll()} da base inteira — pode perder correspondências sem que
 * nada acuse. Num controle de sanções, <b>perder alguém é o único erro que não tem conserto
 * operacional</b>: falso positivo custa minutos de analista, falso negativo é relacionamento com
 * sancionado.
 *
 * <p><b>A assimetria está no piso.</b> Recall exigido em 100%: cada caso do conjunto é um padrão
 * que alguém decidiu que deve casar, e deixar de casar é regressão, não ajuste. Precisão tem piso
 * mais baixo de propósito — falso positivo aqui vira revisão humana, que é o desfecho projetado
 * para match por nome ({@code MatchBasis.NAME} nunca bloqueia sozinho).
 *
 * <p><b>Limitação declarada:</b> o conjunto é sintético e estrutural (ver o cabeçalho do CSV). Ele
 * <b>não</b> estima recall de produção; trava o comportamento do algoritmo nos padrões conhecidos.
 * Casos reais rotulados por analista, com a distribuição de verdade, são o que fecharia a
 * calibragem de fato — e é trabalho próprio.
 */
class ScreeningRecallTest {

  /** O mesmo default de {@code barrier.screening.fuzzy.threshold}. */
  private static final double LIMIAR_EM_PRODUCAO = 0.90;

  private static final List<Caso> CASOS = carregar();

  @Test
  void oConjuntoFoiCarregado() {
    assertThat(CASOS)
        .as("conjunto vazio ou ilegível — as métricas abaixo passariam vacuamente")
        .hasSizeGreaterThan(30);
    assertThat(CASOS.stream().filter(Caso::deveCasar).count()).isGreaterThan(15);
    assertThat(CASOS.stream().filter(c -> !c.deveCasar()).count()).isGreaterThan(10);
  }

  /**
   * Piso de recall em 100%: cada par rotulado MATCH é um padrão que já foi decidido como devendo
   * casar. Uma otimização que perca qualquer um deles quebra o build.
   */
  @Test
  void nenhumPadraoConhecidoDeixaDeCasar() {
    List<Caso> perdidos =
        CASOS.stream().filter(Caso::deveCasar).filter(c -> !casa(c, LIMIAR_EM_PRODUCAO)).toList();

    assertThat(perdidos)
        .as(
            "padroes que deixaram de casar: %s",
            perdidos.stream().map(Caso::descricao).toList())
        .isEmpty();
  }

  /**
   * Falso positivo custa analista, não cliente — por isso o piso é menor, mas existe.
   *
   * <p><b>0.85 e não 0.95, e a curva é que decidiu.</b> Com os negativos difíceis no conjunto, a
   * precisão a 0.90 é ~0.86: quatro pares de quase-homônimo casam de propósito —
   * {@code SILVA}×{@code SILVEIRA}, {@code PINTO}×{@code PINHO}, {@code CLAUDIA}×{@code CLAUDIO},
   * {@code ANDRADE}×{@code ANDRADA}. Subir o limiar para 0.96 levaria a precisão a 1.00 <b>e o
   * recall a 0.92</b> — trocaria quatro revisões manuais por um sancionado não encontrado.
   *
   * <p>Essa é a assimetria que o projeto já assumiu no desenho: {@code MatchBasis.NAME} nunca
   * bloqueia sozinho, vai para julgamento humano. Aceitar os quatro é a decisão correta, e o piso
   * existe para detectar quando ela deixar de valer — precisão caindo abaixo de 0.85 significa
   * ruído novo, não este trade-off.
   */
  @Test
  void aPrecisaoSeMantemAcimaDoPiso() {
    long falsosPositivos =
        CASOS.stream().filter(c -> !c.deveCasar()).filter(c -> casa(c, LIMIAR_EM_PRODUCAO)).count();
    long positivos =
        CASOS.stream().filter(c -> casa(c, LIMIAR_EM_PRODUCAO)).count();

    double precisao = positivos == 0 ? 0 : (positivos - falsosPositivos) / (double) positivos;
    assertThat(precisao)
        .as(
            "precisão caiu para %.2f; falsos positivos: %s",
            precisao,
            CASOS.stream()
                .filter(c -> !c.deveCasar())
                .filter(c -> casa(c, LIMIAR_EM_PRODUCAO))
                .map(Caso::descricao)
                .toList())
        .isGreaterThanOrEqualTo(0.85);
  }

  /**
   * A curva que o limiar deveria ter tido desde o começo. Não é decoração: ela mostra que 0.90 não
   * está numa borda — que mover um pouco para qualquer lado não muda o resultado —, e é o que
   * transforma "0.90 porque pareceu razoável" em "0.90 porque a região é estável".
   *
   * <p>Se um dia a curva mostrar 0.90 numa quina, o limiar está calibrado por sorte.
   */
  @Test
  void oLimiarEscolhidoNaoEstaNumaBorda() {
    StringBuilder curva = new StringBuilder("\nlimiar  recall  precisao\n");
    for (double limiar = 0.80; limiar <= 0.98001; limiar += 0.02) {
      curva.append(
          String.format(
              Locale.ROOT, "%.2f    %.2f    %.2f%n", limiar, recall(limiar), precisao(limiar)));
    }
    System.out.println(curva);

    assertThat(recall(LIMIAR_EM_PRODUCAO - 0.02))
        .as("recall muda ao afrouxar 0.02: o limiar está na borda de perder casos")
        .isEqualTo(recall(LIMIAR_EM_PRODUCAO));
    assertThat(recall(LIMIAR_EM_PRODUCAO + 0.02))
        .as("recall cai ao apertar 0.02: 0.90 está no limite de segurança, não no meio dele")
        .isEqualTo(recall(LIMIAR_EM_PRODUCAO));
  }

  private static double recall(double limiar) {
    long esperados = CASOS.stream().filter(Caso::deveCasar).count();
    long encontrados = CASOS.stream().filter(Caso::deveCasar).filter(c -> casa(c, limiar)).count();
    return esperados == 0 ? 0 : encontrados / (double) esperados;
  }

  private static double precisao(double limiar) {
    long positivos = CASOS.stream().filter(c -> casa(c, limiar)).count();
    long verdadeiros = CASOS.stream().filter(Caso::deveCasar).filter(c -> casa(c, limiar)).count();
    return positivos == 0 ? 0 : verdadeiros / (double) positivos;
  }

  /**
   * Reproduz a decisão do {@code FuzzyNameWatchlistProvider}: cobertura simétrica — basta que um
   * dos nomes cubra o outro, porque no screening não existe lado "oficial".
   */
  private static boolean casa(Caso caso, double limiar) {
    NameTokens consultado = NameTokens.of(caso.consultado());
    NameTokens lista = NameTokens.of(caso.lista());
    return consultado.coveredBy(lista, limiar) || lista.coveredBy(consultado, limiar);
  }

  private static List<Caso> carregar() {
    List<Caso> casos = new ArrayList<>();
    try (InputStream in =
            ScreeningRecallTest.class.getResourceAsStream("/screening/golden-dataset.csv");
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String linha;
      while ((linha = reader.readLine()) != null) {
        if (linha.isBlank() || linha.startsWith("#") || linha.startsWith("consultado;")) {
          continue;
        }
        String[] campos = linha.split(";");
        casos.add(
            new Caso(
                campos[0].trim(),
                campos[1].trim(),
                "MATCH".equals(campos[2].trim()),
                campos[3].trim()));
      }
    } catch (Exception e) {
      throw new IllegalStateException("conjunto rotulado ilegível", e);
    }
    return List.copyOf(casos);
  }

  private record Caso(String consultado, String lista, boolean deveCasar, String categoria) {
    String descricao() {
      return "[" + categoria + "] '" + consultado + "' x '" + lista + "'";
    }
  }
}
