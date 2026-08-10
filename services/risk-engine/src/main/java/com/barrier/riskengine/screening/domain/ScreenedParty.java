package com.barrier.riskengine.screening.domain;

import java.util.Objects;

/**
 * Quem foi consultado nas listas restritivas. O screening não olha só o titular: uma PJ com
 * situação ATIVA, CNAE inócuo e um sócio na SDN era aprovada automaticamente, porque o sócio nunca
 * era consultado — a rota de contorno mais barata que existia, sem falsificar nada.
 *
 * <p>A atribuição não é decoração: sem ela o analista recebe "apontamento em lista de sanções" sem
 * saber se é a empresa ou um sócio, e as duas coisas exigem condutas diferentes.
 *
 * @param role papel da parte na relação
 * @param name nome consultado
 * @param document CPF/CNPJ quando conhecido; {@code null} para sócios do QSA, que a Receita publica
 *     <b>sem documento</b> — por isso o match deles é sempre por nome, e sempre indício
 */
public record ScreenedParty(Role role, String name, String document) {

  public enum Role {
    /** O próprio cliente sendo avaliado. */
    TITULAR,
    /** Sócio do quadro societário direto (QSA). */
    SOCIO,
    /** Representante legal declarado no cadastro. */
    REPRESENTANTE_LEGAL
  }

  public ScreenedParty {
    Objects.requireNonNull(role, "role");
  }

  public static ScreenedParty titular(String name, String document) {
    return new ScreenedParty(Role.TITULAR, name, document);
  }

  public static ScreenedParty socio(String name) {
    return new ScreenedParty(Role.SOCIO, name, null);
  }

  public static ScreenedParty representanteLegal(String name, String document) {
    return new ScreenedParty(Role.REPRESENTANTE_LEGAL, name, document);
  }

  public boolean isTitular() {
    return role == Role.TITULAR;
  }

  /** Rótulo curto para a evidência da decisão (ex.: {@code "sócio JOAO DA SILVA"}). */
  public String label() {
    return switch (role) {
      case TITULAR -> "titular";
      case SOCIO -> "sócio " + name;
      case REPRESENTANTE_LEGAL -> "representante legal " + name;
    };
  }
}
