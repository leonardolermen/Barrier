package com.barrier.riskengine.identity.client;

import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rastro verificável de uma consulta a bureau.
 *
 * <p>Sem isto, "consultamos a Receita via BigBoost" é afirmação nossa sobre nós mesmos: não dá para
 * conferir contra o extrato do provedor numa inspeção, nem reconciliar a fatura, nem investigar uma
 * contestação sem refazer a consulta — que hoje responderia outra coisa.
 *
 * @param providerReference id da consulta do lado do provedor ({@code QueryId} da BigDataCorp);
 *     {@code null} quando a fonte não fornece — a BrasilAPI não tem identificador de consulta, e
 *     registrar a ausência é mais honesto que inventar um id nosso
 * @param rawResponse resposta do bureau <b>com redação</b> dos campos que o projeto decidiu não
 *     guardar; {@code null} quando a persistência do payload está desligada
 */
public record BureauTrace(String providerReference, String rawResponse) {

  /**
   * Campos removidos antes de persistir.
   *
   * <p>Nome da mãe é fator de autenticação clássico, e a decisão de guardar apenas o resultado da
   * comparação — nunca o valor — está documentada no DTO desde que ele existe. Guardar o payload
   * bruto sem redigir desfaria essa decisão em silêncio, que é a pior forma de desfazer uma.
   */
  public static final Set<String> REDACTED_FIELDS = Set.of("MotherName", "MotherTaxIdNumber");

  private static final String REDACTED = "[redigido]";

  /**
   * Extrai o identificador da consulta e produz a versão redigida do payload.
   *
   * @param body corpo da resposta como veio do provedor
   * @param referenceField nome do campo que identifica a consulta na raiz (ex.: {@code QueryId})
   * @param storeRaw se {@code false}, guarda só o identificador — o payload é dado pessoal, e
   *     retenção e criptografia em repouso ainda são pendências da Fase 6
   */
  public static BureauTrace from(
          ObjectMapper mapper, String body, String referenceField, boolean storeRaw) {
    if (body == null || body.isBlank()) {
      return new BureauTrace(null, null);
    }
    try {
      JsonNode root = mapper.readTree(body);
      JsonNode reference = root.get(referenceField);
      String id = reference == null || reference.isNull() ? null : reference.asString();
      return new BureauTrace(id, storeRaw ? redact(root).toString() : null);
    } catch (RuntimeException e) {
      // Rastro é evidência, não decisão: se o corpo não for JSON legível, a verificação continua e
      // a avaliação segue — perder o rastro é ruim, derrubar a decisão por causa dele é pior.
      return new BureauTrace(null, null);
    }
  }

  /** Redação recursiva: o campo sensível pode estar aninhado dentro de {@code BasicData}. */
  private static JsonNode redact(JsonNode node) {
    if (node instanceof ObjectNode object) {
      for (String field : List.copyOf(object.propertyNames())) {
        if (REDACTED_FIELDS.contains(field)) {
          object.put(field, REDACTED);
        } else {
          redact(object.get(field));
        }
      }
    } else if (node != null && node.isArray()) {
      node.forEach(BureauTrace::redact);
    }
    return node;
  }
}
