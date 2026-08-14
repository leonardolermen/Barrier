package com.barrier.riskengine.subject.profile.domain;

/**
 * Resposta verbatim de {@code POST pessoa-fisica/validacao} (Datavalid/Serpro): confere dados
 * <b>declarados</b> contra RFB e, para endereço, contra a base da CNH (SENATRAN) — não é
 * documentoscopia, é verificação de veracidade cadastral.
 *
 * <p><b>Cobertura de endereço é parcial e depende de CNH:</b> {@code cnh.endereco} só existe
 * quando o titular tem CNH com endereço registrado. Sem CNH, {@link #cnh()} vem nulo (ou {@code
 * cnhExiste = false}) e o endereço declarado continua sem verificação — não há base geral de
 * endereços nesta API. Ver
 * {@code docs/implementation/plano-remediacao-auditoria.md} (item "Verificar dados, não só
 * presença").
 */
public record RegistryValidationResult(
    boolean rfbExiste, boolean cnhExiste, Rfb rfb, Cnh cnh, String providerReference) {

  public record Rfb(
      Double nomeSimilaridade,
      Double nomeSocialSimilaridade,
      Boolean situacaoCpf,
      Boolean dataNascimento,
      Boolean dataInscricaoCpf) {}

  public record Cnh(
      Double nomeSimilaridade,
      Boolean dataNascimento,
      Boolean sexo,
      Boolean numeroRegistro,
      Boolean categoria,
      Boolean situacao,
      Boolean dataValidade,
      Endereco endereco) {}

  /** {@code null} quando o titular não tem CNH com endereço registrado — ver Javadoc da classe. */
  public record Endereco(Double logradouroSimilaridade, Boolean cep, Boolean uf) {}
}
