package com.barrier.riskengine.subject.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Mapeamento JPA do subject (cliente final). */
@Entity
@Table(name = "subjects")
@Getter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class SubjectEntity {

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

}
