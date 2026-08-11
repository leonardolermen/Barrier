package com.barrier.webhook.repository;

import org.springframework.data.jpa.repository.JpaRepository;

interface WebhookEndpointJpaRepository extends JpaRepository<WebhookEndpointEntity, String> {}
