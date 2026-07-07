package com.barrier.riskengine.tenant.repository;

import org.springframework.data.jpa.repository.JpaRepository;

interface TenantJpaRepository extends JpaRepository<TenantEntity, String> {}
