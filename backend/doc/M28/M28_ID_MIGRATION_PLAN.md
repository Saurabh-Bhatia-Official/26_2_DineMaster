# M28 Integer ID to UUID Migration Plan

**Status:** Proposed additive migration; no code or database files changed.  
**Date:** 2026-11-24

## 1. Recommendation

Do **not** replace integer primary keys in the first M28 release. Add a stable UUID identity alongside every syncable integer row, retain integer PK/FK relationships and existing REST path IDs, and use UUIDs only in sync envelopes and cross-database references. This avoids a risky all-at-once FK rewrite and protects current Flutter integer models and Spring `Long` APIs.

The UUID becomes the canonical cross-database identity. Integer IDs remain local implementation keys and can differ between SQLite and PostgreSQL.

## 2. Why direct PK replacement is unsafe here

- SQLite tables use `INTEGER PRIMARY KEY AUTOINCREMENT`; local screens and joins expect numeric IDs.
- PostgreSQL entities use `BIGINT GENERATED ... AS IDENTITY`; Java entities and REST paths use `Long`.
- Foreign keys span orders, order items, products, tables, users, customers, KOT, bookings, inventory and recipes. Changing PK types requires coordinated DDL, ORM, DTO, request validation and UI changes.
- Not every SQLite row has a direct PostgreSQL table. Some cloud domains split or normalize local tables; payments have no cloud ledger currently.
- Integer values can coincidentally match across databases without identifying the same record.

## 3. Additive identity model

For each syncable table, add:

| Field | SQLite proposal | PostgreSQL proposal | Purpose |
|---|---|---|---|
| `sync_id` | `TEXT`, canonical UUID string, unique and non-null after backfill | `UUID`, unique in tenant/branch scope where appropriate | Stable identity across Edge and cloud |
| `version` | `INTEGER NOT NULL DEFAULT 1` for mutable rows | retain existing `version` where present; add where missing | Optimistic concurrency for mutable entities |
| `updated_at` | UTC ISO-8601 text | `TIMESTAMPTZ` | Auditing and conflict diagnostics; not the pull cursor by itself |
| `deleted_at` | optional UTC text for cloud-owned soft-deletable masters | optional `TIMESTAMPTZ` | Propagate safe soft deletes for master data |

Append-only rows (payments, status changes, KOT events, stock movements) use a unique event UUID and immutable payload. They do not use “update this event to version N” semantics.

Add an ID map (proposed) with `entity_type`, `sync_id`, `source_system`, `source_integer_id`, `target_system`, `target_integer_id`, `mapping_status`, timestamps, and a uniqueness constraint per source identity. The table's exact physical name is a schema decision; no such general mapping currently exists.

Related entity references in push/pull payloads use UUIDs. Each database resolves UUIDs to its own integer FK. Existing local and cloud FKs remain unchanged in the first rollout.

## 4. Migration sequence

### Step A: inventory and backups

1. Freeze the migration scope and identify all Edge host files, old backup files, cloud branches and tenant mappings.
2. Take verified backups of every SQLite file and PostgreSQL. Record SQLite `PRAGMA user_version`, PostgreSQL Flyway history and table counts.
3. Run FK integrity checks, orphan reports, duplicate natural-key reports, and financial totals (orders, order items, invoices and whatever payments can be represented in the cloud).
4. Build a rehearsal environment from copies; never rehearse against the only production copy.

### Step B: add nullable UUID columns and mapping table

1. Add nullable `sync_id` columns and unique indexes concurrently/online where supported. Keep existing PK/FK columns unchanged.
2. Add any missing mutable-row version/updated timestamps with safe defaults. Do not treat security `permission_version` as a generic entity version.
3. Add explicit payment ledger and other absent cloud domains in separate reviewed migrations before syncing those entity types.
4. Deploy readers/writers that understand nullable UUIDs but continue existing integer behavior.

### Step C: backfill stable UUIDs

1. Generate UUIDs once per row in batches and persist them. UUID generation may be random UUIDv4; UUIDv7 is acceptable if the supported libraries are agreed, but ordering must not depend on UUID time.
2. Create a source-side identity map for each database. Preserve a durable source key `(system, table, integer_id)`.
3. For rows that are proven to represent the same business object in both databases, map them to one shared UUID only after an audited reconciliation decision.
4. For independent rows, retain distinct UUIDs. Equal integer IDs are never proof of a match.
5. Re-run counts, FK checks and totals after the backfill. UUID values must remain stable through subsequent reruns.

### Step D: map foreign references

Keep existing local integer FKs. Build UUID relationships by joining each source FK to its row UUID. On incoming cloud changes, resolve related UUIDs to the local integer ID map. Refuse or quarantine an event when a required reference is missing; do not guess or create a substitute product/user/table.

### Step E: compatibility and cutover

1. Keep REST routes and DTO numeric IDs unchanged. Add UUIDs to sync-only DTOs or additive response fields; avoid exposing UUID as an API path replacement in this phase.
2. Dual-read temporarily: use numeric ID for existing screen/API behavior and UUID for sync identity. Add logs for missing/duplicate UUID maps.
3. Enable sync for one test branch with catalog bootstrap only, then append-only low-risk events, then orders/payments after reconciliation.
4. Keep rollback available: disable sync, preserve UUIDs/outbox, continue old local behavior. Do not roll back/drop additive columns after events have been accepted.
5. Consider replacing integer PKs only in a later major API version, after all consumers and all FK relationships have moved and a separate migration is approved.

## 5. Existing record reconciliation

| Data | Safe match candidates | Unsafe shortcut / required treatment |
|---|---|---|
| Restaurant/branch | Verified branch code plus tenant identity; manual approval for legacy default restaurant | Local `restaurant_id=1` equals cloud `id=1` |
| Table | Branch UUID + stable table number/code; check uniqueness | Matching local/cloud numeric ID or display name alone |
| Product/category | Explicit external code if present; otherwise reviewed branch/name/category matching with duplicates resolved | Product name alone, because names may repeat/change |
| User | Cloud identity/employee code under tenant; verify account ownership | Username alone across tenants or importing local password hashes blindly |
| Customer | Tenant-scoped verified external ID or reviewed phone/email identity | Phone-only global merge; phone can be shared/reused |
| Order | Existing explicit common UUID/idempotency key if available; otherwise manual reconciliation by branch, timestamp, order contents/amount and legacy references | Integer ID, date/amount only, or regenerating IDs |
| Payment | Explicit ledger transaction/reference | Current order payment method/status is not a payment transaction identity |

The current SQLite orders have no cloud-style `order_no` in the base schema. PostgreSQL has `order_no` and backfilled legacy numbers. A migration must preserve both legacy references and cloud invoice/legal numbers; it must not silently overwrite an issued invoice number.

## 6. API and Flutter compatibility

- Java entity IDs, repository signatures, current order endpoints and Flutter `int` IDs remain untouched initially.
- Sync DTOs carry both `syncId` and optional `legacyId` for diagnostics only; the receiving service acts on UUID identity and its validated branch mapping.
- API response UUID fields can be additive and nullable during rollout. Do not allow a client-supplied legacy integer ID to select a record outside its authorized branch.
- For an offline-created order, the Edge allocates its local integer ID and UUID. Cloud creates/locates its own integer row and records the UUID-to-cloud-ID mapping. Pull can then update the Edge's cloud-side mapping without rewriting the Edge's integer PK.
- Any API that accepts related IDs must resolve or validate those IDs within the same tenant/branch. Cross-system foreign keys must be translated by UUID first.

## 7. Rollback and data safety

- Rollback means stop creating/sending sync events or disable the feature flag, not remove IDs already in use.
- Keep a durable outbox until server acknowledgement. Preserve failed and conflicting payloads.
- Retain pre-migration backups until count/totals checks pass and an agreed retention period ends.
- No destructive `DROP`, PK replacement, or FK rewrite is part of this additive phase.
- Any record that cannot be mapped remains in its source database and appears in a reconciliation report; do not discard it to make sync pass.

