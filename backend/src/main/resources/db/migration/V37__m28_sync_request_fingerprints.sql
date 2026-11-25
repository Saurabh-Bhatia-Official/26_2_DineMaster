-- Bind each idempotency receipt to the exact submitted change content.
-- Existing rows intentionally remain unverifiable and are handled fail-closed.
ALTER TABLE ops.edge_processed_changes ADD COLUMN request_hash CHAR(64);
