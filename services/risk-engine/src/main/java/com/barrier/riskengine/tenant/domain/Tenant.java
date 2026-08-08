package com.barrier.riskengine.tenant.domain;

/** Cliente da API (empresa que consome o Barrier). Autenticado por API key. */
public record Tenant(String id, String name, boolean active) {}
