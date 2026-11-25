-- M28 Phase 3A development foundation. Additive only; V34 is intentionally unchanged.
CREATE TABLE ops.edge_host_activations (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    activation_code_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_edge_activation_branch_tenant
      FOREIGN KEY (branch_id, tenant_id) REFERENCES restaurants(id, tenant_id)
);
CREATE INDEX ix_edge_activation_expiry ON ops.edge_host_activations(expires_at);

CREATE TABLE ops.edge_hosts (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    public_key_pem TEXT NOT NULL,
    public_key_sha256 CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','REVOKED','REPLACED')),
    sync_epoch BIGINT NOT NULL DEFAULT 1 CHECK (sync_epoch > 0),
    registered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT fk_edge_host_branch_tenant
      FOREIGN KEY (branch_id, tenant_id) REFERENCES restaurants(id, tenant_id)
);
CREATE UNIQUE INDEX ux_edge_host_active_branch ON ops.edge_hosts(tenant_id, branch_id)
    WHERE state = 'ACTIVE';
CREATE UNIQUE INDEX ux_edge_host_public_key ON ops.edge_hosts(public_key_sha256);

INSERT INTO permissions(code, description)
VALUES ('edge_host:activate', 'Issue one-time activation codes for Edge hosts')
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('OWNER','MANAGER') AND p.code = 'edge_host:activate'
ON CONFLICT DO NOTHING;
