# M28 Database Mapping

**Status:** Phase 2 finalized schema mapping; additive SQLite and PostgreSQL schema changes are implemented in the workspace. No production database was modified.  
**Date:** 2026-11-24

## 1. How to read this document

SQLite schema is created/upgraded in `frontend/lib/core/database/database_helper.dart` (Phase 2 advances it to version 8). PostgreSQL schema is built by Flyway migrations under `backend/src/main/resources/db/migration` (Phase 2 adds V34). Backend JPA entities are under `backend/src/main/java/com/dinemaster/**/entity`.

The mapping below is intentionally not a claim that schemas are interchangeable. SQLite uses local integer IDs and looser constraints; PostgreSQL uses `BIGINT` identities, tenant/branch scope, more normalized tables, domain constraints, and richer lifecycle fields. An em dash means no same-purpose table was found in the inspected schema. Some app tables are created/altered lazily by `_ensureTablesExist`, so legacy databases may vary by upgrade history.

## 2. Identifier/version policy recommended for M28

- Preserve every existing integer primary key and foreign key. Do not rewrite API paths or Flutter models in the first migration.
- Add an immutable cross-database UUID (`sync_id` in SQLite as canonical UUID text; PostgreSQL `UUID`) to each syncable row. Keep integer PKs as local/cloud implementation keys.
- Add unique `(tenant/branch scope, sync_id)` constraints as appropriate. References in sync payloads use related rows' `sync_id`; each side resolves those UUIDs to its own integer IDs.
- Add an explicit mapping record for bootstrap/reconciliation (entity, source database, source integer ID, sync UUID, destination integer ID, resolution status). Never infer identity from equal integer IDs.
- Add `version` (starting at 1) and `updated_at` to mutable syncable rows that lack them. Existing PostgreSQL versions are present only on selected entities noted below. Append-only ledger/event rows need immutable event UUIDs, not last-write versions.
- Add `deleted_at` only for cloud-owned mutable master data that supports soft deletion. Business events are reversed/cancelled through compensating events; do not delete financial history.
- UUID columns, versioning, timestamps and mapping metadata are implemented additively in Phase 2 for the allowlisted entities. They do not authorize or enable synchronization.

## 3. Core SQLite to PostgreSQL map

| SQLite table | PostgreSQL counterpart | Current SQLite PK/FKs | Current PostgreSQL PK/FKs / shape | Existing version/sync fields | Proposed UUID/version and migration notes |
|---|---|---|---|---|---|
| `restaurants` | `restaurants` (also the branch table) | `id INTEGER`; no tenant or branch code in SQLite base schema | `id BIGINT`; `tenant_id` logically required; branch code, timezone, active, `version`, `deleted_at` added by V18 | SQLite none; PG `version` exists | Add `sync_id`; reconcile each legacy restaurant to a tenant/branch; do not assume local restaurant 1 equals cloud branch 1. |
| `users` | `users`, plus `user_branches`, `user_roles`, `roles`, `permissions` | `id INTEGER`; SQLite has `username`, password hash, single text role; no branch link in base table | `id BIGINT`; `tenant_id`, optional `restaurant_id`; branch assignment and role/permission relations are separate tables | SQLite none; PG `permission_version` is security version, not row sync version | Add UUID mapping, but do not sync local password/role as operational outbox data. Cloud identity/roles win; bootstrap users securely and never downgrade password hashes. |
| `customers` | `customers` | `id INTEGER`; phone unique; no restaurant/tenant column in base table | `id BIGINT`; tenant-scoped customer fields | No common sync version | Add `sync_id`, tenant/branch ownership, version and `updated_at`; define phone matching/PII policy; don't merge by phone without tenant review. |
| `tables` | `tables`, plus `floors` | `id INTEGER`; optional `waiter_id`, `restaurant_id`, `merged_with_id` | `id BIGINT`; `waiter_id`, `restaurant_id`, `floor_id`; V20 adds coordinates and `version` | SQLite none; PG `version` exists | Add table UUID; map legacy sections/types to cloud `section`, `table_type`, `floors`; distinguish cloud layout from Edge live status. |
| `products` | `products`, `branch_products`, `tax_slabs`, possibly `menu_price_audit` | `id INTEGER`; optional restaurant ID; category stored as text; product-specific recipe/display fields | `id BIGINT`; category text plus category relation/branch availability and tax slab; tenant/branch scope | SQLite none; PG product has no observed JPA `@Version`/migration version | Add UUID and `version`; normalize category and tax references; map `price`, availability and tax snapshots carefully. Cloud owns catalog/price; don't push offline edits as committed changes. |
| `categories` | `categories` | `id INTEGER`; unique name; optional restaurant | `id BIGINT`; name/restaurant; menu metadata evolves in later migrations | No common version | Add UUID/version/updated timestamp; resolve uniqueness from global SQLite `name` to branch-aware cloud uniqueness before migration. |
| `orders` | `orders`, `order_status_logs`, `order_events_outbox`, `invoices`, `order_number_counters` | `id INTEGER`; optional table/customer/waiter/restaurant refs; `total_amount REAL`; no stable order number in base schema | `id BIGINT`; tenant, branch, table/customer/waiter/session/creator refs; order number; subtotal/tax/rounding/discount; idempotency keys; `version`; lifecycle timestamps | SQLite none; PG `version`, idempotency keys and backend order-event outbox exist | Add `sync_id`; preserve integer IDs; generate/assign stable order UUID before local insert; map references via UUID; do not synthesize a cloud match from integer ID. Event-based order lifecycle, branch order-number policy and baseline reconciliation required. |
| `order_items` | `order_items`, `kot_items`, `order_recipe_snapshots` | `id INTEGER`; refs to local order/product/KOT; quantity, price, notes/status | `id BIGINT`; refs to order/product; tax snapshot/station/cancel fields and version; KOT item links separate | SQLite none; PG `version` exists | Add UUID and order UUID relation; item changes should be events, preserve item snapshots/tax. Cloud validates referenced product mapping. |
| `kot` | `kot`, `kot_items`, `kitchen_events`, `kitchen_delays`, `print_jobs` | `id INTEGER`; order FK; timestamps/status/chef fields; no cloud station/round/branch constraints | `id BIGINT`; order and branch; station/round/urgent; item snapshot rows, events and print jobs | No common row version; event sequencing structures exist in PG | Add UUIDs; retain KOT numbering per Edge branch; sync KOT/status events append-only; avoid replacing KDS timeline with a stale snapshot. |
| `order_status_logs` | `order_status_logs` | `id INTEGER`; order ID; status/time/changed-by text/notes; no declared FK in base DDL | `id BIGINT`; order FK; entity type/id; from/to status; actor/approver/device; created time | Append-only by design; no version | Add UUID event ID; map actor and order by UUID; extend local audit attribution without deleting old log rows. |
| `payments` | `payment_events` (new append-only cloud ledger) | `id INTEGER`; `order_id`; amount REAL, mode, time | New BIGINT identity plus UUID `sync_id`; order/tenant/branch scope; immutable PAYMENT/REVERSAL events; unique tenant/idempotency key | SQLite receives UUID sidecar, PAYMENT default event type, reversal link, optional currency, idempotency key; PostgreSQL ledger is new in V34 | Existing payment rows remain in SQLite and are assigned stable UUID/idempotency keys. Existing cloud order payment fields are not copied into fabricated events. Currency and payment posting semantics remain pending before API wiring. |
| `bookings` | `bookings` | `id INTEGER`; table/customer/restaurant refs; booking time/status/notes; some fields added during create/upgrade | `id BIGINT`; table/customer/branch and tenant fields; overlap/business validation | No common version | Add UUID/version/updated time; map name/phone snapshots and timezone; resolve collisions through booking validation/review. |
| `inventory` | `inventory`, `stock_batches`, `stock_movements` | `id INTEGER`; restaurant ref; `current_stock`, unit, threshold, expiry | `id BIGINT`; richer tenant/branch, costing, batches and movement ledger | No common version | Add UUID; migrate opening balance to a signed opening movement/batch; thereafter sync movement UUIDs, never overwrite stock balance. |
| `recipes` | `recipes` + `recipe_items` (SQLite shape is legacy lines) | `id INTEGER`; `product_id`, `ingredient_id` (references `inventory`), quantity; older normalized recipe-per-line shape | `recipes` versioned per product; `recipe_items` references recipe, product and inventory | Cloud recipe has domain version; local no equivalent | Reconcile current SQLite rows to recipe headers/items; preserve lines; cloud owns recipe configuration. |
| `ingredients` | Usually `inventory`, but **not a direct schema match** | `id INTEGER`; name/unit/stock_quantity/restaurant | `inventory` stores item name/current stock/unit and cloud inventory also has batch/movement model | None | Decide whether this table is a separate ingredient catalog or a duplicate inventory view before mapping; don't blindly merge by name. |
| `table_types`, `table_sections` | `tables.table_type`, `tables.section`; no dedicated cloud tables found | Integer IDs, unique name, optional restaurant | Text values on `tables`; floor/layout has dedicated `floors` | None | Convert to cloud lookup values/text with branch scope; local normalization can remain behind repository. |
| `suppliers` | — (no matching PG table found) | `id INTEGER`; name/contact/address/restaurant | No matching migration/entity found | None | Out of M28 initial sync until a cloud supplier contract is approved. Preserve locally; don't drop. |
| `vendors` | — (no matching PG table found) | SQLite table in base schema | No matching migration/entity found | None | No mapping. Requires product decision or new cloud domain schema. |
| `expenses` | — (no matching PG table found) | `id INTEGER`; description/amount/date/category/restaurant | No matching migration/entity found | None | No mapping. Exclude from sync until cloud accounting ownership and schema exist. |
| `offers` | — (no matching PG table found) | `id INTEGER`; unique code, percentage, active | No matching migration/entity found | None | No mapping. Catalog ownership and redemption ledger require separate design. |
| `loyalty_transactions` | — (no matching PG ledger found) | `id INTEGER`; customer, points, type/date | Customer has points field; no cloud transaction ledger found | SQLite append-like rows only | Add cloud loyalty ledger before syncing; use immutable transaction UUIDs, not customer points overwrite. |
| `suppliers` / lazy `ingredients` / `recipes` support | Partial/overlap as above | Additional tables are created by `_ensureTablesExist` | PG has recipe/inventory domains but not every SQLite support table | None consistent | Inventory every live SQLite file and schema version before migration; current helper creates some tables after opening rather than only in `onCreate`. |

## 4. Important PostgreSQL-only tables/fields

These are not present as same-purpose SQLite tables in the base schema and should not be copied as generic rows: `tenants`, `user_branches`, `user_roles`, `roles`, `permissions`, `auth_devices`, refresh/audit/activation tables, `branch_hours`, `floors`, `branch_products`, `tax_slabs`, `kot_items`, `print_jobs`, stock batches/movements, invoices/counters, settings/audit, notifications, waiter assignments, table status logs, kitchen event streams, and backend outboxes. They need explicit bootstrap or event projections with security and tenant rules.

## 4.1 Finalized Phase 2 entity allowlist

The additive identity foundation applies to `restaurants`, `users`, `customers`, `tables`, `categories`, `products`, `orders`, `order_items`, `kot`, `order_status_logs`, `payments` (mapped to the new cloud `payment_events` ledger), `bookings`, and `inventory`. The presence of a UUID does not authorize synchronization; all cloud identity mappings remain `UNRECONCILED` until an operator-reviewed match exists.

Excluded from Phase 2 identity/backfill pending a separate domain mapping: `ingredients`, `recipes` (SQLite line shape differs from cloud recipe headers/items), `suppliers`, `vendors`, `expenses`, `offers`, `loyalty_transactions`, `table_types`, `table_sections`, and `print_jobs`. Backend-only tables without equivalent local business rows also remain excluded. No excluded local tables or records are removed.

## 5. Outbox/cursor/conflict schema inventory

| Name | Current status | M28 recommendation |
|---|---|---|
| SQLite `sync_outbox` | Not found | Add local transactional outbox with UUID change ID, UUID entity ID, operation, immutable payload, branch/device, sequence, base version, retry metadata and status. |
| SQLite `sync_state` | Not found | Add cursor per stream, device ID, schema version, sync epoch, last success and status. |
| SQLite `sync_conflicts` | Not found | Add durable local/server payloads, conflict category/rule, status, resolution actor/time. |
| PostgreSQL `ops.sync_cursors` | Not found | Add device/branch/stream-scoped cursor state or equivalent; cursor tokens must be opaque and stable. |
| PostgreSQL `processed_changes` | Not found under that name | Add unique `change_id` receipt table and persist result atomically with domain apply. Do not mistake `processed_events` for this table; V25 defines it for backend recipe/order event processing. |
| PostgreSQL `staff_sync_outbox` | Exists, staff snapshot publisher | Keep distinct; it is not general edge replication. |
| PostgreSQL `order_events_outbox` | Exists, backend order event publisher | Keep distinct from Edge-to-cloud outbox unless a reviewed design unifies event delivery. |

## 6. Data reconciliation rules for migration

1. Snapshot and inventory each SQLite file and PostgreSQL branch. Record table counts, max IDs, FK violations, order totals, payment totals and schema versions.
2. Create UUID sidecars without changing integer keys or FK columns. Generate one stable UUID per extant row and persist it before any sync begins.
3. Do not match rows because local and cloud integer IDs are equal. Match only with validated identifiers (e.g. explicit UUID, verified branch/table code, approved import key). Ambiguous orders and customers need review.
4. Keep source/destination integer key maps. Resolve every FK through the map before applying a change. If a related UUID is unknown, reject/quarantine the event; never substitute a same-number ID.
5. Take a consistent backup and reconcile totals before enabling push. The Edge's legacy order numbers are generated/displayed differently from cloud order numbers; choose the authoritative number/counter and preserve old numbers as legacy display/reference values.
6. Keep unknown SQLite-only tables and rows untouched. No table in this plan is to be dropped as part of an additive sync migration.

## 7. Evidence and limitations

The SQLite DDL includes tables defined in `_createDB`, while `_ensureTablesExist` and `_onUpgrade` also add/create tables and columns. A fresh database and an older upgraded database may therefore differ. The Phase 2 PostgreSQL migration chain was run on a disposable database, but no production database or real Edge file was inspected. Inventory every target database and compare its schema/data before any production deployment.

