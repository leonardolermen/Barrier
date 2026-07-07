package com.barrier.riskengine.subject.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mapeamento JPA do subject (cliente final). */
@Entity
@Table(name = "subjects")
class SubjectEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "document_type", nullable = false, length = 10)
  private String documentType;

  @Column(name = "document", nullable = false, length = 20)
  private String document;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected SubjectEntity() {
    // JPA
  }

  SubjectEntity(UUID id, String documentType, String document, String name, Instant createdAt) {
    this.id = id;
    this.documentType = documentType;
    this.document = document;
    this.name = name;
    this.createdAt = createdAt;
  }

  UUID getId() {
    return id;
  }

  String getDocumentType() {
    return documentType;
  }

  String getDocument() {
    return document;
  }

  String getName() {
    return name;
  }

  Instant getCreatedAt() {
    return createdAt;
  }
}
