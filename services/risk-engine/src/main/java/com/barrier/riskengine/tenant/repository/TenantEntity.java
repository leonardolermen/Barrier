package com.barrier.riskengine.tenant.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Mapeamento JPA de um tenant (cliente da API). */
@Entity
@Table(name = "tenants")
@Getter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantEntity {

  @Id
  @Column(name = "id", nullable = false, length = 40)
  private String id;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Column(name = "active", nullable = false)
  private boolean active;

}
