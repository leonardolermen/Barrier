package com.barrier.riskengine.identity.domain;

import java.time.LocalDate;

/**
 * Dados cadastrais objetivos de uma <b>pessoa física</b>, obtidos do bureau na verificação de
 * identidade. É o simétrico de {@link CompanyProfile}, que já existia para PJ.
 *
 * <p>Existia um buraco assimétrico: os dados objetivos de PJ vinham do bureau e eram persistidos no
 * cadastro, mas os de PF não tinham por onde entrar — o {@code BureauResult} só carregava perfil de
 * empresa. O efeito prático era que <b>toda</b> avaliação de pessoa física era rebaixada para
 * revisão por cadastro incompleto, mesmo com o bureau tendo respondido, porque nascimento e
 * endereço nunca chegavam ao {@code SubjectProfile}.
 *
 * <p><b>O que o bureau não resolve:</b> ocupação é declaração do cliente, não dado de bureau (o
 * {@code basic_data} da BigBoost não a publica). Esse campo continua dependendo do parceiro, e é
 * legítimo que continue — é justamente o tipo de informação que a diligência exige que o cliente
 * declare.
 *
 * @param birthDate data de nascimento
 * @param nationality nacionalidade, quando a fonte a informa
 * @param address endereço residencial, quando a fonte o informa
 */
public record PersonProfile(
    LocalDate birthDate, String nationality, Address address) {

  /**
   * Endereço como o bureau devolve. Duplicado de {@code SubjectProfile.Address} de propósito: o
   * módulo identity não conhece o cadastro, mesma separação que {@link CompanyProfile.Partner} já
   * faz — a tradução acontece em quem persiste.
   */
  public record Address(
      String street,
      String number,
      String complement,
      String district,
      String city,
      String state,
      String zipCode) {}
}
