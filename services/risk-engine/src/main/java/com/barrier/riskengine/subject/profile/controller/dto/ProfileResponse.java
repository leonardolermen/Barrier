package com.barrier.riskengine.subject.profile.controller.dto;

import com.barrier.riskengine.subject.profile.domain.RegistrationCompleteness;
import java.util.List;

/**
 * Resultado de uma atualização de cadastro: o checklist de completude (CMN 4.753) para o tipo de
 * documento.
 *
 * <p><b>Não devolve o cadastro.</b> Devolvia, e como {@code SubjectProfilePatch} preserva todo
 * campo nulo, um {@code PUT} com corpo vazio não alterava nada e retornava o dossiê inteiro —
 * endereço, telefone, e-mail, nascimento, renda declarada, representante legal. Com o vínculo
 * criado por um simples {@code POST /v1/assessments}, isso dava a qualquer parceiro autenticado a
 * base cadastral dos clientes de todos os outros.
 *
 * <p>A proveniência por tenant (migration V024) já isola a escrita e a leitura no banco; não
 * devolver o objeto é a segunda camada — quem chama recebe apenas o efeito da própria escrita, que
 * é a informação de que precisa para saber se falta algum campo.
 */
public record ProfileResponse(boolean complete, List<String> missingFields) {

  public static ProfileResponse of(RegistrationCompleteness completeness) {
    return new ProfileResponse(completeness.complete(), completeness.missingFields());
  }
}
