package com.barrier.riskengine.tenant.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Mapeamento JPA de um tenant (cliente da API). */
@Entity
@Table(name = "tenants")
class TenantEntity {

  @Id
  @Column(name = "id", nullable = false, length = 40)
  private String id;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected TenantEntity() {
    // JPA
  }

  String getId() {
    return id;
  }

  String getName() {
    return name;
  }

  boolean isActive() {
    return active;
  }
}
