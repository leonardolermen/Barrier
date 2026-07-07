package com.barrier.riskengine.tenant.domain;

/** Cliente da API (empresa que consome o Barrier). Identificado pelo header {@code X-Client-Id}. */
public record Tenant(String id, String name, boolean active) {}
