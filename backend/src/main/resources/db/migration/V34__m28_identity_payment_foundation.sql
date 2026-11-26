-- M28 Phase 2: additive identity, device/cursor receipts, and payment ledger.
-- Existing BIGINT identities and all existing foreign keys remain unchanged.

CREATE SCHEMA IF NOT EXISTS ops;

-- Composite keys allow the sync registration tables to enforce that the
-- authenticated device and selected branch belong to the same tenant.
ALTER TABLE restaurants ADD CONSTRAINT uq_restaurants_id_tenant UNIQUE (id, tenant_id);
ALTER TABLE auth_devices ADD CONSTRAINT uq_auth_devices_id_tenant UNIQUE (id, tenant_id);

-- UUID sidecars provide cross-database identity without replacing API/ORM IDs.
ALTER TABLE restaurants ADD COLUMN sync_id UUID;
ALTER TABLE users ADD COLUMN sync_id UUID;
ALTER TABLE customers ADD COLUMN sync_id UUID;
ALTER TABLE tables ADD COLUMN sync_id UUID;
ALTER TABLE categories ADD COLUMN sync_id UUID;
ALTER TABLE products ADD COLUMN sync_id UUID;
ALTER TABLE orders ADD COLUMN sync_id UUID;
ALTER TABLE order_items ADD COLUMN sync_id UUID;
ALTER TABLE kot ADD COLUMN sync_id UUID;
ALTER TABLE order_status_logs ADD COLUMN sync_id UUID;
ALTER TABLE bookings ADD COLUMN sync_id UUID;
ALTER TABLE inventory ADD COLUMN sync_id UUID;

UPDATE restaurants SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
UPDATE users SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
UPDATE customers SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
UPDATE tables SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
UPDATE categories SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
UPDATE products SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
UPDATE orders SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
UPDATE order_items SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
UPDATE kot SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
UPDATE order_status_logs SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
UPDATE bookings SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;
UPDATE inventory SET sync_id = gen_random_uuid() WHERE sync_id IS NULL;

ALTER TABLE restaurants ALTER COLUMN sync_id SET NOT NULL;
ALTER TABLE users ALTER COLUMN sync_id SET NOT NULL;
ALTER TABLE customers ALTER COLUMN sync_id SET NOT NULL;
ALTER TABLE tables ALTER COLUMN sync_id SET NOT NULL;
ALTER TABLE categories ALTER COLUMN sync_id SET NOT NULL;
ALTER TABLE products ALTER COLUMN sync_id SET NOT NULL;
ALTER TABLE orders ALTER COLUMN sync_id SET NOT NULL;
ALTER TABLE order_items ALTER COLUMN sync_id SET NOT NULL;
ALTER TABLE kot ALTER COLUMN sync_id SET NOT NULL;
ALTER TABLE order_status_logs ALTER COLUMN sync_id SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN sync_id SET NOT NULL;
ALTER TABLE inventory ALTER COLUMN sync_id SET NOT NULL;

CREATE UNIQUE INDEX ux_restaurants_sync_id ON restaurants(sync_id);
CREATE UNIQUE INDEX ux_users_sync_id ON users(sync_id);
CREATE UNIQUE INDEX ux_customers_sync_id ON customers(sync_id);
CREATE UNIQUE INDEX ux_tables_sync_id ON tables(sync_id);
CREATE UNIQUE INDEX ux_categories_sync_id ON categories(sync_id);
CREATE UNIQUE INDEX ux_products_sync_id ON products(sync_id);
CREATE UNIQUE INDEX ux_orders_sync_id ON orders(sync_id);
CREATE UNIQUE INDEX ux_order_items_sync_id ON order_items(sync_id);
CREATE UNIQUE INDEX ux_kot_sync_id ON kot(sync_id);
CREATE UNIQUE INDEX ux_order_status_logs_sync_id ON order_status_logs(sync_id);
CREATE UNIQUE INDEX ux_bookings_sync_id ON bookings(sync_id);
CREATE UNIQUE INDEX ux_inventory_sync_id ON inventory(sync_id);

-- Version/timestamp fields are for mutable rows. Existing @Version values on
-- restaurants, tables, orders and order_items are retained. Security's
-- permission_version remains a separate concern and is not repurposed.
ALTER TABLE restaurants ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE users ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE customers ADD COLUMN version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0);
ALTER TABLE customers ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE tables ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE categories ADD COLUMN version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0);
ALTER TABLE products ADD COLUMN version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0);
ALTER TABLE products ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE orders ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE order_items ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE kot ADD COLUMN version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0);
ALTER TABLE kot ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE bookings ADD COLUMN version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0);
ALTER TABLE bookings ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE inventory ADD COLUMN version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0);
ALTER TABLE inventory ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- The map is deliberately unresolved until a reviewed cross-database match is
-- supplied. Equal integer IDs are never treated as evidence of identity.
CREATE TABLE ops.sync_identity_map (
    entity_type VARCHAR(60) NOT NULL,
    sync_id UUID NOT NULL,
    edge_id BIGINT,
    cloud_id BIGINT,
    mapping_status VARCHAR(24) NOT NULL DEFAULT 'UNRECONCILED'
        CHECK (mapping_status IN ('UNRECONCILED','MATCHED','EDGE_ONLY','CLOUD_ONLY','REVIEW_REQUIRED')),
    resolution_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (entity_type, sync_id),
    CHECK (edge_id IS NOT NULL OR cloud_id IS NOT NULL)
);
CREATE UNIQUE INDEX ux_sync_identity_map_edge
    ON ops.sync_identity_map(entity_type, edge_id) WHERE edge_id IS NOT NULL;
CREATE UNIQUE INDEX ux_sync_identity_map_cloud
    ON ops.sync_identity_map(entity_type, cloud_id) WHERE cloud_id IS NOT NULL;

CREATE TABLE ops.sync_control (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    sync_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    reconciliation_mode BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO ops.sync_control(singleton) VALUES (TRUE);

INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'restaurants', sync_id, id FROM restaurants;
INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'users', sync_id, id FROM users;
INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'customers', sync_id, id FROM customers;
INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'tables', sync_id, id FROM tables;
INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'categories', sync_id, id FROM categories;
INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'products', sync_id, id FROM products;
INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'orders', sync_id, id FROM orders;
INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'order_items', sync_id, id FROM order_items;
INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'kot', sync_id, id FROM kot;
INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'order_status_logs', sync_id, id FROM order_status_logs;
INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'bookings', sync_id, id FROM bookings;
INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id)
SELECT 'inventory', sync_id, id FROM inventory;

-- Existing application APIs continue inserting rows with their integer IDs.
-- Generate the additive UUID sidecar for those inserts and register the cloud
-- row without guessing whether a corresponding Edge record exists.
CREATE FUNCTION ops.assign_sync_identity() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.sync_id IS NULL THEN
        NEW.sync_id = gen_random_uuid();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION ops.register_sync_identity() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO ops.sync_identity_map(entity_type, sync_id, cloud_id, mapping_status)
    VALUES (TG_TABLE_NAME, NEW.sync_id, NEW.id, 'CLOUD_ONLY');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    entity_table TEXT;
BEGIN
    FOREACH entity_table IN ARRAY ARRAY[
        'restaurants','users','customers','tables','categories','products',
        'orders','order_items','kot','order_status_logs','bookings','inventory'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION ops.assign_sync_identity()',
            'trg_' || entity_table || '_assign_sync_identity', entity_table);
        EXECUTE format(
            'CREATE TRIGGER %I AFTER INSERT ON %I FOR EACH ROW EXECUTE FUNCTION ops.register_sync_identity()',
            'trg_' || entity_table || '_register_sync_identity', entity_table);
    END LOOP;
END;
$$;

CREATE FUNCTION ops.prevent_sync_id_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.sync_id IS DISTINCT FROM OLD.sync_id AND NOT EXISTS (
        SELECT 1 FROM ops.sync_control
        WHERE singleton AND reconciliation_mode AND NOT sync_enabled
    ) THEN
        RAISE EXCEPTION 'sync_id is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_restaurants_sync_id_immutable BEFORE UPDATE ON restaurants
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();
CREATE TRIGGER trg_users_sync_id_immutable BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();
CREATE TRIGGER trg_customers_sync_id_immutable BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();
CREATE TRIGGER trg_tables_sync_id_immutable BEFORE UPDATE ON tables
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();
CREATE TRIGGER trg_categories_sync_id_immutable BEFORE UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();
CREATE TRIGGER trg_products_sync_id_immutable BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();
CREATE TRIGGER trg_orders_sync_id_immutable BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();
CREATE TRIGGER trg_order_items_sync_id_immutable BEFORE UPDATE ON order_items
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();
CREATE TRIGGER trg_kot_sync_id_immutable BEFORE UPDATE ON kot
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();
CREATE TRIGGER trg_order_status_logs_sync_id_immutable BEFORE UPDATE ON order_status_logs
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();
CREATE TRIGGER trg_bookings_sync_id_immutable BEFORE UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();
CREATE TRIGGER trg_inventory_sync_id_immutable BEFORE UPDATE ON inventory
    FOR EACH ROW EXECUTE FUNCTION ops.prevent_sync_id_change();

CREATE FUNCTION ops.touch_sync_version() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    IF NEW.version IS NOT DISTINCT FROM OLD.version THEN
        NEW.version = OLD.version + 1;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION ops.touch_sync_timestamp() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_sync_timestamp BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION ops.touch_sync_timestamp();

CREATE TRIGGER trg_restaurants_sync_version BEFORE UPDATE ON restaurants
    FOR EACH ROW EXECUTE FUNCTION ops.touch_sync_version();
CREATE TRIGGER trg_customers_sync_version BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION ops.touch_sync_version();
CREATE TRIGGER trg_tables_sync_version BEFORE UPDATE ON tables
    FOR EACH ROW EXECUTE FUNCTION ops.touch_sync_version();
CREATE TRIGGER trg_categories_sync_version BEFORE UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION ops.touch_sync_version();
CREATE TRIGGER trg_products_sync_version BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION ops.touch_sync_version();
CREATE TRIGGER trg_orders_sync_version BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION ops.touch_sync_version();
CREATE TRIGGER trg_order_items_sync_version BEFORE UPDATE ON order_items
    FOR EACH ROW EXECUTE FUNCTION ops.touch_sync_version();
CREATE TRIGGER trg_kot_sync_version BEFORE UPDATE ON kot
    FOR EACH ROW EXECUTE FUNCTION ops.touch_sync_version();
CREATE TRIGGER trg_bookings_sync_version BEFORE UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION ops.touch_sync_version();
CREATE TRIGGER trg_inventory_sync_version BEFORE UPDATE ON inventory
    FOR EACH ROW EXECUTE FUNCTION ops.touch_sync_version();

-- Existing auth_devices already carry UUID identity and may carry branch_id.
-- This table records sync eligibility/epoch separately from auth credential
-- issuance; Phase 2 does not create device credentials or authorize a device.
CREATE TABLE ops.sync_device_state (
    device_id UUID PRIMARY KEY REFERENCES auth_devices(id) ON DELETE RESTRICT,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    branch_id BIGINT NOT NULL REFERENCES restaurants(id) ON DELETE RESTRICT,
    sync_epoch BIGINT NOT NULL DEFAULT 1 CHECK (sync_epoch > 0),
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ,
    CONSTRAINT fk_sync_device_tenant FOREIGN KEY (device_id, tenant_id)
        REFERENCES auth_devices(id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_sync_device_branch_tenant FOREIGN KEY (branch_id, tenant_id)
        REFERENCES restaurants(id, tenant_id) ON DELETE RESTRICT,
    CONSTRAINT uq_sync_device_scope UNIQUE (device_id, tenant_id, branch_id)
);
CREATE INDEX ix_sync_device_state_branch_active
    ON ops.sync_device_state(tenant_id, branch_id, active);

CREATE TABLE ops.sync_cursors (
    device_id UUID NOT NULL REFERENCES ops.sync_device_state(device_id) ON DELETE CASCADE,
    stream_key VARCHAR(100) NOT NULL,
    cursor_value TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (device_id, stream_key)
);

CREATE TABLE ops.processed_changes (
    change_id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    branch_id BIGINT NOT NULL REFERENCES restaurants(id) ON DELETE RESTRICT,
    entity_type VARCHAR(60) NOT NULL,
    entity_sync_id UUID NOT NULL,
    result VARCHAR(24) NOT NULL CHECK (result IN ('ACCEPTED','CONFLICT','REJECTED')),
    response JSONB NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_processed_change_device_scope
        FOREIGN KEY (device_id, tenant_id, branch_id)
        REFERENCES ops.sync_device_state(device_id, tenant_id, branch_id) ON DELETE RESTRICT,
    CONSTRAINT fk_processed_change_branch_tenant
        FOREIGN KEY (branch_id, tenant_id)
        REFERENCES restaurants(id, tenant_id) ON DELETE RESTRICT
);
CREATE INDEX ix_processed_changes_branch_time
    ON ops.processed_changes(tenant_id, branch_id, processed_at DESC);

-- Immutable payment ledger foundation. The event row, not orders.payment_* 
-- fields, is the durable financial record. API/domain writes are not switched
-- to this table in Phase 2.
CREATE TABLE payment_events (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    sync_id UUID NOT NULL UNIQUE,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    branch_id BIGINT NOT NULL REFERENCES restaurants(id) ON DELETE RESTRICT,
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('PAYMENT','REVERSAL')),
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    payment_method VARCHAR(50),
    idempotency_key VARCHAR(100) NOT NULL,
    reverses_event_id UUID REFERENCES payment_events(sync_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    source_device_id UUID REFERENCES auth_devices(id) ON DELETE RESTRICT,
    actor_user_id BIGINT REFERENCES users(id) ON DELETE RESTRICT,
    external_reference VARCHAR(150),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_payment_event_reversal_link CHECK (
        (event_type = 'PAYMENT' AND reverses_event_id IS NULL)
        OR (event_type = 'REVERSAL' AND reverses_event_id IS NOT NULL)
    ),
    CONSTRAINT ck_payment_method_required_for_payment CHECK (
        event_type <> 'PAYMENT' OR payment_method IS NOT NULL
    ),
    CONSTRAINT ck_payment_idempotency_nonempty CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT uq_payment_event_idempotency UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX ix_payment_events_order_time ON payment_events(order_id, created_at, id);
CREATE INDEX ix_payment_events_branch_time ON payment_events(tenant_id, branch_id, created_at DESC);

-- Mutable concurrency guard for reversal limits. The payment event ledger is
-- still immutable; this small projection serializes competing reversals using
-- one atomic conditional UPDATE rather than a snapshot-sensitive SUM query.
CREATE TABLE ops.payment_reversal_totals (
    payment_sync_id UUID PRIMARY KEY
        REFERENCES payment_events(sync_id) ON DELETE RESTRICT,
    payment_amount NUMERIC(12,2) NOT NULL CHECK (payment_amount > 0),
    reversed_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (reversed_amount >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (reversed_amount <= payment_amount)
);

CREATE FUNCTION ops.validate_payment_event() RETURNS TRIGGER AS $$
DECLARE
    original payment_events%ROWTYPE;
    event_order_tenant BIGINT;
    event_order_branch BIGINT;
BEGIN
    SELECT tenant_id, restaurant_id INTO event_order_tenant, event_order_branch
      FROM orders WHERE id = NEW.order_id;
    IF NOT FOUND OR event_order_tenant IS DISTINCT FROM NEW.tenant_id
       OR event_order_branch IS DISTINCT FROM NEW.branch_id THEN
        RAISE EXCEPTION 'payment event order, tenant, or branch scope does not match';
    END IF;
    IF NEW.source_device_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM auth_devices d
        WHERE d.id = NEW.source_device_id AND d.tenant_id = NEW.tenant_id
          AND d.branch_id = NEW.branch_id AND d.active
    ) THEN
        RAISE EXCEPTION 'payment source device is not active for this branch';
    END IF;
    IF NEW.actor_user_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM users u
        JOIN user_branches ub ON ub.user_id = u.id
        WHERE u.id = NEW.actor_user_id AND u.tenant_id = NEW.tenant_id
          AND ub.branch_id = NEW.branch_id
    ) THEN
        RAISE EXCEPTION 'payment actor is outside this tenant';
    END IF;

    IF NEW.event_type = 'REVERSAL' THEN
        SELECT * INTO original FROM payment_events
          WHERE sync_id = NEW.reverses_event_id;
        IF NOT FOUND OR original.event_type <> 'PAYMENT'
           OR original.order_id <> NEW.order_id
           OR original.tenant_id <> NEW.tenant_id
           OR original.branch_id <> NEW.branch_id
           OR original.currency <> NEW.currency THEN
            RAISE EXCEPTION 'reversal must reference a payment for the same order, branch, and currency';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_validate_payment_event
    BEFORE INSERT ON payment_events
    FOR EACH ROW EXECUTE FUNCTION ops.validate_payment_event();

CREATE FUNCTION ops.track_payment_reversal_total() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.event_type = 'PAYMENT' THEN
        INSERT INTO ops.payment_reversal_totals(payment_sync_id, payment_amount)
        VALUES (NEW.sync_id, NEW.amount);
    ELSE
        -- The conditional UPDATE locks and rechecks the latest total after a
        -- concurrent transaction commits. If it exceeds the original, raising
        -- here rolls back both this projection update and the inserted event.
        UPDATE ops.payment_reversal_totals
           SET reversed_amount = reversed_amount + NEW.amount,
               updated_at = CURRENT_TIMESTAMP
         WHERE payment_sync_id = NEW.reverses_event_id
           AND reversed_amount + NEW.amount <= payment_amount;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'reversal total exceeds original payment or payment is missing';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_track_payment_reversal_total
    AFTER INSERT ON payment_events
    FOR EACH ROW EXECUTE FUNCTION ops.track_payment_reversal_total();

CREATE FUNCTION ops.reject_payment_event_mutation() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.sync_id IS DISTINCT FROM OLD.sync_id
           AND (to_jsonb(NEW) - 'sync_id') = (to_jsonb(OLD) - 'sync_id')
           AND EXISTS (SELECT 1 FROM ops.sync_control
                       WHERE singleton AND reconciliation_mode AND NOT sync_enabled) THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'payment events are immutable; append a reversal event instead';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_events_immutable
    BEFORE UPDATE OR DELETE ON payment_events
    FOR EACH ROW EXECUTE FUNCTION ops.reject_payment_event_mutation();


