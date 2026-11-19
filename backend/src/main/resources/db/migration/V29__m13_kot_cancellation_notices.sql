ALTER TABLE kot ADD COLUMN ticket_kind VARCHAR(30) NOT NULL DEFAULT 'ORDER'
    CHECK (ticket_kind IN ('ORDER', 'CANCEL_NOTICE'));
CREATE INDEX ix_kot_branch_kind_created ON kot(branch_id, ticket_kind, created_at DESC);
