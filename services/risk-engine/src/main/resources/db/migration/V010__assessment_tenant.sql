ALTER TABLE assessments ADD COLUMN tenant_id VARCHAR(40) NOT NULL DEFAULT 'default';

ALTER TABLE assessments
    ADD CONSTRAINT fk_assessments_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);

CREATE INDEX idx_assessments_tenant ON assessments (tenant_id);
