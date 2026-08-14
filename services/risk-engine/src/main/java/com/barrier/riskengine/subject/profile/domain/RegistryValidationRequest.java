package com.barrier.riskengine.subject.profile.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

/**
 * Campos declarados a conferir na validação cadastral (Datavalid/Serpro,
 * {@code POST pessoa-fisica/validacao}) contra RFB/SENATRAN.
 *
 * <p><b>Só inclui o que foi declarado</b> — {@code cpf} é o único obrigatório; todo o resto é
 * nullable e {@link JsonInclude.Include#NON_NULL} garante que campo ausente não vira {@code
 * "campo": null} no corpo. A API valida o que recebe: mandar campo vazio consome cota e não
 * verifica nada, então o chamador ({@code RegistryValidationService}) só preenche o que o
 * parceiro efetivamente declarou no cadastro.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegistryValidationRequest(
    // Ignorado na serialização deste objeto: no corpo de POST pessoa-fisica/validacao o cpf é
    // irmão de "validacao", não campo dela (ver contrato) — o CPF de topo é montado à parte por
    // quem serializa (SerproRegistryValidationProvider.WireRequest).
    @JsonIgnore String cpf,
    String nome,
    LocalDate dataNascimento,
    String sexo,
    String nacionalidade,
    String nomeMae,
    String nomePai,
    String tipoDocumentoOrigem,
    String numeroDocumentoOrigem,
    String orgaoExpedidorDocumentoOrigem,
    String ufExpedidorDocumentoOrigem,
    Endereco endereco,
    Rfb rfb,
    Cnh cnh) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Endereco(
      String logradouro,
      String numero,
      String complemento,
      String bairro,
      String cep,
      String municipio,
      String uf) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Rfb(String nomeSocial, String situacaoCpf, LocalDate dataInscricaoCpf) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Cnh(String numeroRegistro, String categoria, String situacao, LocalDate dataValidade) {}
}
