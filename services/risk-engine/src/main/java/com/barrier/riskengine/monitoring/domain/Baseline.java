package com.barrier.riskengine.monitoring.domain;

import java.util.List;
import java.util.Optional;

/**
 * Expectativa móvel para uma série — o que torna estes alertas úteis.
 *
 * <p><b>Por que não limiar fixo.</b> Os dois modos de falha que mais custam caro são silenciosos e
 * nenhum deles cruza um limiar absoluto: o parceiro que <i>parou</i> de mandar (volume cai, nada
 * estoura) e a regra que passou a <i>aprovar tudo</i> (taxa sobe, nada estoura). Só se enxerga
 * ambos comparando com o que costuma acontecer.
 *
 * <p><b>Como a sazonalidade é tratada.</b> A comparação é sempre contra <b>a mesma hora do dia, em
 * dias anteriores</b>. Volume às 3h da manhã não se parece com volume às 15h, e comparar a hora
 * corrente com a média das 24h anteriores acusaria queda todo fim de expediente — alerta que grita
 * todo dia no mesmo horário é alerta que o time aprende a ignorar, o que é pior que não ter alerta.
 * Amostrar a mesma hora resolve fim de semana e madrugada sem precisar modelar "janela comercial".
 *
 * <p><b>Amostra insuficiente não alerta.</b> Abaixo de {@link #MIN_SAMPLES} observações históricas
 * não há expectativa, e o avaliador se cala. Sem esta trava, uma instalação nova (ou uma base
 * recém-migrada) dispararia todos os alertas no primeiro dia, e a primeira experiência do time com
 * o alerta seria ele mentindo.
 */
public record Baseline(double expected, int samples) {

  /** Menos que isto é ruído, não expectativa. */
  public static final int MIN_SAMPLES = 3;

  /**
   * Média das observações históricas, ou vazio se não há amostra suficiente.
   *
   * <p>Média e não mediana de propósito: com 3 a 14 amostras a mediana descarta informação demais,
   * e o que se quer aqui é ordem de grandeza, não estimador robusto. O custo é conhecido — um pico
   * histórico infla a expectativa e atrasa o alerta de queda —, e a margem configurável já absorve.
   */
  public static Optional<Baseline> of(List<? extends Number> historico) {
    List<? extends Number> validas = historico.stream().filter(java.util.Objects::nonNull).toList();
    if (validas.size() < MIN_SAMPLES) {
      return Optional.empty();
    }
    double soma = validas.stream().mapToDouble(Number::doubleValue).sum();
    return Optional.of(new Baseline(soma / validas.size(), validas.size()));
  }

  /** Verdadeiro se {@code observado} está abaixo de {@code fracao} da expectativa. */
  public boolean abaixoDe(double observado, double fracao) {
    return observado < expected * fracao;
  }

  /** Verdadeiro se {@code observado} está acima de {@code fator} vezes a expectativa. */
  public boolean acimaDe(double observado, double fator) {
    return observado > expected * fator;
  }
}
