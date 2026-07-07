ALTER TABLE deliveries ADD COLUMN tenant_id VARCHAR(40);

CREATE INDEX idx_deliveries_tenant ON deliveries (tenant_id);
