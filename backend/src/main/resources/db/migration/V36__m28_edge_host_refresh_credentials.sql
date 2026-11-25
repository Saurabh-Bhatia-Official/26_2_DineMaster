-- Rotating host refresh-token families. Raw credentials are never stored.
CREATE TABLE ops.edge_host_refresh_families (
    id UUID PRIMARY KEY,
    host_id UUID NOT NULL REFERENCES ops.edge_hosts(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_edge_refresh_family_host ON ops.edge_host_refresh_families(host_id, revoked_at);

CREATE TABLE ops.edge_host_refresh_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    family_id UUID NOT NULL REFERENCES ops.edge_host_refresh_families(id) ON DELETE CASCADE,
    host_id UUID NOT NULL REFERENCES ops.edge_hosts(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_edge_refresh_tokens_family ON ops.edge_host_refresh_tokens(family_id, created_at);

CREATE TABLE ops.edge_sync_cursors (
    host_id UUID NOT NULL REFERENCES ops.edge_hosts(id) ON DELETE CASCADE,
    stream_key VARCHAR(80) NOT NULL,
    cursor_value TEXT NOT NULL DEFAULT '',
    pending_cursor TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (host_id, stream_key)
);

CREATE TABLE ops.edge_processed_changes (
    change_id UUID PRIMARY KEY,
    host_id UUID NOT NULL REFERENCES ops.edge_hosts(id) ON DELETE RESTRICT,
    result VARCHAR(20) NOT NULL CHECK (result IN ('ACCEPTED','CONFLICT','REJECTED')),
    response JSONB NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
