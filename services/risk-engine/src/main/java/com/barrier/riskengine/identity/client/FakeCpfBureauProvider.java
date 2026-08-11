package com.barrier.riskengine.identity.client;

import com.barrier.riskengine.identity.domain.PersonProfile;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Bureau de CPF simulado para desenvolvimento e teste, enquanto não há credencial real.
 *
 * <p>Substitui o {@code StubBureauProvider}, que respondia MATCH para qualquer CPF — um mock que
 * aprova tudo não exercita nenhum caminho do pipeline além do feliz, e foi preciso inventar um
 * readiness guard só para impedir que ele chegasse em produção.
 *
 * <p><b>Qualquer CPF válido é atendido, e o desfecho é escolhido pelo prefixo:</b> um CPF que
 * começa com {@code 999} entra em modo cenário e o <b>quarto dígito</b> diz qual; qualquer outro
 * CPF é {@code REGULAR}. É a convenção dos cartões de teste da Stripe, e pelo mesmo motivo — quem
 * está testando quer <i>escolher</i> o desfecho, não descobri-lo.
 *
 * <p>A primeira versão derivava o cenário de um hash do CPF. Determinístico, mas arbitrário: o
 * {@code 111.444.777-35} usado em metade dos testes caía em "titular falecido", e cada CPF conhecido
 * passava a dar um resultado surpresa. Previsibilidade vale mais que variedade aqui — o mock existe
 * para dirigir cenários, não para simular uma população.
 *
 * <p><b>O que este mock NÃO faz:</b> ele não valida que a BigBoost responde no formato que o
 * {@code BigBoostBureauProvider} espera. Isso é coberto por {@code BigBoostBureauProviderTest},
 * que passa o JSON documentado pelo parser real. Confundir as duas coisas — "temos mock" com
 * "temos integração verificada" — é como o CSV da CGU entrou no repositório sem verificação.
 *
 * <p>Nunca sobe em {@code prod} ({@link Profile}) e nunca é autoritativo ({@link
 * BureauProvider#authoritative()}), então também não serve de fallback para um bureau real
 * indisponível.
 */
@Component
@Profile("!prod")
@Order(100) // último da cadeia: só entra quando não há bureau real de CPF
public class FakeCpfBureauProvider implements BureauProvider {

  private static final Logger log = LoggerFactory.getLogger(FakeCpfBureauProvider.class);

  /** Prefixo que coloca o CPF em modo cenário; sem ele, o desfecho é sempre REGULAR. */
  static final String SCENARIO_PREFIX = "999";

  /** Desfechos que o pipeline precisa saber tratar. A ordem define o dígito seletor. */
  enum Scenario {
    REGULAR,
    TITULAR_FALECIDO,
    OBITO_SEM_STATUS,
    SUSPENSA,
    PENDENTE,
    NULA,
    NAO_ENCONTRADO,
    INDISPONIVEL,
    /**
     * CPF regular, mas o bureau responde <b>sem</b> os dados cadastrais. Existe para o caminho de
     * "cadastro incompleto" continuar exercitável em dev depois que o mock passou a devolver perfil
     * completo: um mock que sempre completa o cadastro esconderia o rebaixamento para revisão, que
     * é justamente o gate da CMN 4.753.
     */
    REGULAR_SEM_CADASTRO;

    /**
     * Cenário de um CPF: {@code 999X…} usa {@code X} como seletor, qualquer outro é REGULAR.
     *
     * <p>Seletor fora da faixa também cai em REGULAR — um CPF que por acaso comece com {@code 999}
     * não deve virar "falecido" sem que alguém tenha pedido.
     */
    static Scenario of(String cpfDigits) {
      if (cpfDigits == null || cpfDigits.length() != 11 || !cpfDigits.startsWith(SCENARIO_PREFIX)) {
        return REGULAR;
      }
      int selector = cpfDigits.charAt(SCENARIO_PREFIX.length()) - '0';
      return selector >= 0 && selector < values().length ? values()[selector] : REGULAR;
    }
  }

  @Override
  public boolean supports(String documentType) {
    return "CPF".equals(documentType);
  }

  @Override
  public BureauResult check(BureauQuery query) {
    Scenario scenario = Scenario.of(query.documentDigits());
    log.debug("Bureau simulado: cenário {}", scenario);
    return switch (scenario) {
      case REGULAR ->
          BureauResult.of(
              BureauResult.Outcome.MATCH, "simulado: CPF regular na Receita", perfilSimulado());
      case REGULAR_SEM_CADASTRO ->
          BureauResult.match("simulado: CPF regular, bureau sem dados cadastrais");
      case TITULAR_FALECIDO ->
          new BureauResult(
              BureauResult.Outcome.DECEASED, "simulado: situação do CPF na Receita: TITULAR FALECIDO");
      case OBITO_SEM_STATUS ->
          new BureauResult(
              BureauResult.Outcome.DECEASED,
              "simulado: situação REGULAR (com indicação de óbito)");
      case SUSPENSA ->
          new BureauResult(BureauResult.Outcome.MISMATCH, "simulado: situação do CPF: SUSPENSA");
      case PENDENTE ->
          new BureauResult(
              BureauResult.Outcome.MISMATCH,
              "simulado: situação do CPF: PENDENTE DE REGULARIZACAO");
      case NULA -> new BureauResult(BureauResult.Outcome.NOT_FOUND, "simulado: situação do CPF: NULA");
      case NAO_ENCONTRADO ->
          new BureauResult(BureauResult.Outcome.NOT_FOUND, "simulado: CPF não encontrado");
      case INDISPONIVEL ->
          throw new BureauUnavailableException("simulado: bureau indisponível");
    };
  }

  /**
   * Dados cadastrais que um bureau real de PF devolve — é o que o {@code SubjectProfile} recebe,
   * e sem isso toda avaliação de pessoa física em dev caía em revisão por cadastro incompleto,
   * escondendo o caminho de aprovação automática atrás de um problema de ambiente.
   *
   * <p>Fixo de propósito: o mock existe para dirigir cenários, não para simular uma população. Quem
   * quiser o cadastro faltando usa o cenário {@code 9998…}.
   *
   * <p>Ocupação fica de fora porque bureau nenhum a fornece — é declaração do cliente, e continuar
   * cobrando ela do parceiro é o comportamento correto.
   */
  private static PersonProfile perfilSimulado() {
    return new PersonProfile(
        LocalDate.of(1990, 1, 1),
        "Brasileira",
        new PersonProfile.Address(
            "Rua Simulada", "100", null, "Centro", "São Paulo", "SP", "01000-000"));
  }

  @Override
  public String name() {
    return "simulado";
  }

  /** Simulação não confirma identidade — ver {@link BureauProvider#authoritative()}. */
  @Override
  public boolean authoritative() {
    return false;
  }
}
