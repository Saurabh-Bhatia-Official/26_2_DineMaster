# M28 Phase 2 Implementation Report

**Scope:** Phase 1 repository validation and Phase 2 identity, schema, and bootstrap migration foundation only.  
**Date:** 2026-11-24  
**Production databases:** Not accessed or modified.

## Existing architecture validation

- Flutter uses a local SQLite database managed by `DatabaseHelper`, currently schema version 7. A device configured as a LAN client uses the Edge host's Shelf SQL proxy; it does not have a second local SQLite database. The M28 migration is applied by the Edge database owner.
- Spring Boot 3.3.4 uses PostgreSQL through Flyway in normal deployments and H2 for the test profile. Flyway migrations were at V33 before this work.
- Integer primary keys and foreign keys remain the application-facing identifiers. Existing Spring REST endpoints, entity IDs, Flutter model IDs, and payment/order endpoint behavior were not changed.
- Existing cloud `staff_sync_outbox`, `order_events_outbox`, and `processed_events` are specialized backend mechanisms. There was no general Edge sync outbox, M28 sync API, cursor pull, or automatic synchronization. None was added here.
- Existing PostgreSQL payment behavior stores payment status/method on orders; SQLite has a `payments` table. There was no PostgreSQL payment ledger before Phase 2.

## Completed implementation

- Added UUID `sync_id` sidecars for the finalized entity allowlist while retaining every legacy integer ID and relationship.
- Added version/timestamp tracking for mutable allowlisted records and immutable UUID protections. Existing PostgreSQL `@Version` columns remain in place; `users.permission_version` was not reused as a data version.
- Added explicit Edge/cloud integer-ID mapping registries. Existing cloud rows are registered as `UNRECONCILED`; local/cloud records are never matched because their integer IDs happen to be equal.
- Added device binding state, branch/tenant scope constraints, sync epoch/schema version, cursor, processed-change receipt, and disabled sync-control schemas. Device state remains inactive and local state remains unbound.
- Added a PostgreSQL payment-event ledger repository supporting immutable payment and reversal events, scoped order validation, tenant/idempotency uniqueness, replay checks, and reversal limits. Existing order/payment endpoints are not wired to the new ledger.
- Added backup, inventory, UUID backfill, and explicit reviewed-mapping tools. They refuse to infer record identity. The SQLite maintenance tool requires `--apply` for writes; reconciliation requires reviewed matches and an explicit assertion that the cloud SQL transaction was committed.
- Finalization review made the post-open SQLite metadata pass transactional, made payment idempotency replay validate branch identity and amount precision, and changed concurrent reversal limits to an atomic conditional accumulator.
- No outbox, automatic sync trigger, sync endpoint, conflict resolver, Phase 3 shared write path, or UI was added.

## Final Phase 2 entity allowlist and exclusions

The identity foundation covers `restaurants`, `users`, `customers`, `tables`, `categories`, `products`, `orders`, `order_items`, `kot`, `order_status_logs`, `payments` (cloud counterpart: `payment_events`), `bookings`, and `inventory`.

Excluded pending a separate domain/schema decision: `ingredients`, `recipes` (SQLite legacy lines differ from PostgreSQL recipe headers/items), `suppliers`, `vendors`, `expenses`, `offers`, `loyalty_transactions`, `table_types`, `table_sections`, and `print_jobs`. Their tables and records remain untouched. A UUID alone does not make an entity eligible for synchronization.

## Files created and modified

**Created**

- `backend/src/main/resources/db/migration/V34__m28_identity_payment_foundation.sql`
- `backend/src/main/java/com/dinemaster/sync/SyncIdentityMapRepository.java`
- `backend/src/main/java/com/dinemaster/orders/repository/PaymentLedgerRepository.java`
- `backend/src/test/java/com/dinemaster/sync/SyncIdentityMapRepositoryTest.java`
- `backend/src/test/java/com/dinemaster/sync/M28MigrationContractTest.java`
- `backend/src/test/java/com/dinemaster/orders/repository/PaymentLedgerRepositoryTest.java`
- `frontend/lib/core/database/m28_sqlite_migration.dart`
- `frontend/test/m28_identity_migration_test.dart`
- `tools/m28/m28_sqlite.py`
- `tools/m28/test_m28_sqlite.py`
- `M28_PHASE_2_IMPLEMENTATION_REPORT.md`

**Modified**

- `frontend/lib/core/database/database_helper.dart` — verified pre-upgrade backup, schema version 8, additive migration hook.
- `backend/src/main/resources/schema-test.sql` — H2 test-profile support tables.
- `M28_DATABASE_MAPPING.md` — finalized Phase 2 allowlist and payment mapping.

The four other M28 planning documents were reviewed; they remain architecture/planning references.

## SQLite schema changes

The version 8 migration adds `sync_id` to allowlisted tables, `version` to mutable tables missing it, and `updated_at` where applicable. `payments` receives `event_type` (legacy rows default to `PAYMENT`), `reversal_of_sync_id`, `currency_code`, and `idempotency_key`. New payment rows get an idempotency value based on their generated UUID unless the caller supplies one.

New local metadata tables are `sync_identity_map`, `sync_state`, `sync_cursors`, and `sync_device_state`. Identity rows start `UNRECONCILED`; device binding starts `UNBOUND`. There is deliberately no `sync_outbox` in Phase 2. Before an existing database upgrade, `VACUUM INTO` creates a unique sibling backup and the backup is checked with `PRAGMA integrity_check`; a backup failure stops the upgrade.

## PostgreSQL schema changes

Flyway V34 adds UUID `sync_id` sidecars and unique indexes on allowlisted cloud entities. It adds missing `version`/`updated_at` fields without replacing the existing integer keys, foreign keys, or `@Version` fields. It creates `ops.sync_identity_map`, `ops.sync_control`, `ops.sync_device_state`, `ops.sync_cursors`, `ops.processed_changes`, and `payment_events`.

`ops.sync_control.sync_enabled` and `reconciliation_mode` both default to false. Device eligibility is inactive by default and references existing tenant/branch-scoped `auth_devices`. Processed-change receipts require a registered device binding. These tables prepare later phases; no API writes to them yet.

## UUID mapping strategy

Each legacy row receives a generated stable UUID stored on that row; the integer ID remains unchanged and is retained in the mapping table. PostgreSQL rows begin cloud-only in the mapping registry with `UNRECONCILED` status; SQLite rows begin edge-only and `UNRECONCILED`. A cross-database match requires an explicit reviewed decision and a shared canonical UUID. The maintenance utility exports a review template and generates guarded SQL; it does not compare or equate integer IDs automatically.

## Payment ledger implementation

`payment_events` stores append-only `PAYMENT` and `REVERSAL` events with UUID identity, order/tenant/branch scope, amount/currency, idempotency key, optional actor/device, and a link from reversals to the original payment. A database trigger rejects update/delete, validates order scope and registered device/actor scope, enforces same-order/same-currency reversals, and prevents cumulative reversal amounts from exceeding the original payment. A unique `(tenant_id, idempotency_key)` constraint plus repository replay validation prevents duplicate or changed-request reuse. No existing payment route posts to this table in Phase 2.

## Migration and rollback instructions

1. Back up each Edge SQLite file and the PostgreSQL database before deploying. The application also creates a verified SQLite sibling backup before upgrading an existing file. Keep the Edge host stopped while using the maintenance backfill tool.
2. Deploy the backend migration through the existing Flyway startup process. V34 was validated only on a disposable local PostgreSQL database, never on production.
3. For operator inventory, run `python tools/m28/m28_sqlite.py inventory <edge.sqlite>`; for an explicit SQLite backup, use `backup <edge.sqlite> <backup.sqlite>`; for a reviewed local UUID backfill, use `backfill <edge.sqlite> --apply` after stopping the Edge host.
4. To roll back an Edge upgrade, stop the application, preserve the upgraded file for diagnosis, and restore the matching pre-upgrade backup to the original database path. Do not delete the backup until the restored database passes integrity and business-data checks.
5. V34 is additive and has no destructive down migration. If it must be reversed, restore the coordinated PostgreSQL snapshot taken before V34, or deploy a reviewed forward-fix. Do not manually drop the new tables/columns after application data may have been written.
6. For reconciliation, review/export the mapping template, apply the generated guarded SQL in a cloud transaction, then invoke the local apply step with `--apply --cloud-transaction-committed`. Keep sync disabled throughout.

## Test execution results

- **PostgreSQL migration chain:** The latest V34 was applied through Flyway to clean and populated disposable PostgreSQL 18.6 databases. The populated V33-to-V34 fixture preserved IDs, FKs, totals, and UUID mappings; Flyway validated the migration set and later reported v34 up to date without a checksum mismatch. Real PostgreSQL reversal/idempotency races, branch/tenant isolation, and the focused payment repository suite passed. See `M28_PHASE_2_FINAL_VALIDATION_REPORT.md`. No production or Neon connection was used.
- **Flutter targeted M28 test:** passed after correcting the insert/update version-trigger interaction.
- **Flutter full suite:** latest final-validation rerun passed, 47 tests (includes file-backed backup/restore and interrupted-migration rollback coverage).
- **Backend full suite:** latest final-validation rerun of `backend\\mvnw.cmd -q test` passed (108 tests, 0 failures, 0 errors) after correcting compilation and Spring exception-translation expectations.
- **Payment ledger focused suite:** `backend\\mvnw.cmd -q -Dtest=PaymentLedgerRepositoryTest test` passed after adding idempotent reversal replay coverage with a nullable payment method.
- **Python SQLite tooling tests:** Python 3.12.10 was invoked by its full interpreter path because the shell `python` command resolves to a Windows Store alias. The latest full rerun passed 6 tests, 0 failures, 0 errors, including deterministic cleanup and Windows handle-release regressions. The sandboxed attempt could not create SQLite files in its temporary directory; rerunning outside the sandbox passed.

## Remaining risks and pending decisions

- Existing cloud payment status fields are not converted into ledger events. Historical financial reconciliation policy must be decided; inventing events from current order fields would risk inaccurate records.
- Currency source, refunds/partial reversals policy, payment method normalization, and whether all legacy payment rows have trustworthy amounts remain business decisions. The ledger requires explicit currency and is not endpoint-integrated.
- Device credential issuance and authorization, allowed sync device classes, branch binding lifecycle, and backup-restore epoch reset procedure remain security/operations decisions. The schemas are inactive and do not authorize sync.
- Edge SQLite customer/user/branch ownership fields are less complete than cloud tenant/branch scope. Bootstrap mapping must resolve ownership explicitly before sync enablement.
- No live production database inventory was possible or performed. Real Edge files may have schema drift beyond checked-in version-7 source.
- Final validation also exercised V34 through Flyway on a representative populated disposable V33 database. This synthetic fixture is not a production snapshot; actual Edge ownership and any deployment-specific schema drift remain unverified.

## Phase 2 acceptance criteria

- [x] Existing integer IDs, FKs, REST routes, and Flutter model IDs remain intact.
- [x] Allowlist and exclusions are recorded.
- [x] SQLite migration is additive and creates a verified backup before upgrading an existing file.
- [x] PostgreSQL migration is additive and validated through the migration chain on an isolated database.
- [x] UUIDs and reviewed-only cross-database mappings are implemented.
- [x] Version/timestamp, device/branch binding state, epoch, cursor, processed receipt, and disabled sync-control schemas exist.
- [x] PostgreSQL payment ledger and idempotency/reversal protections are implemented without changing existing payment endpoints.
- [x] Targeted and full Flutter tests pass.
- [x] Full backend suite passed (108 tests), and the focused payment-ledger suite passed with reversal replay coverage.
- [x] Python 3.12.10 maintenance-tool tests executed: 6 passed, 0 failures/errors.

## Phase 3 readiness status

**Not ready to start.** Phase 2 validation is complete, but Phase 3 remains blocked on Edge/LAN domain-operation authentication, product/security approval of device identity and credential lifecycle, branch binding and restore epochs, branch currency defaults, Web operational write routing, finance-approved treatment of legacy payment history, and verified ownership reconciliation from actual Edge files. Confirm the deployment PostgreSQL version against the Flyway release's supported range. Phase 2 intentionally has not enabled automatic synchronization.
