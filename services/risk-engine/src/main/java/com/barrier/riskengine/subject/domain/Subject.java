package com.barrier.riskengine.subject.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * O cliente final avaliado (CPF/CNPJ). Entidade global: um registro por documento, compartilhado
 * entre os tenants que o avaliaram. O tipo de documento fica como String para manter o módulo
 * independente do módulo assessment.
 */
public record Subject(
    UUID id, String documentType, String document, String name, Instant createdAt) {

  public static Subject create(String documentType, String document, String name) {
    return new Subject(UUID.randomUUID(), documentType, document, name, Instant.now());
  }
}
