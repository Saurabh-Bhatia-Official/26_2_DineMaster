# M28 Phase 2 Final Validation Report

**Date:** 2026-11-24  
**Scope:** Phase 2 finalization only. No Phase 3 implementation or production database access was performed.  
**Read:** `M28_PHASE_2_IMPLEMENTATION_REPORT.md` and all five M28 planning documents.

## Completed fixes

- Wrapped the post-open SQLite M28 schema pass in a transaction. The database upgrade callback already runs within sqflite's upgrade transaction; the additional pass after lazy table creation now also rolls back as one unit.
- Added file-backed SQLite migration coverage for a populated legacy fixture, preserved order/item/payment rows and payment totals, foreign-key checks, backup integrity, backup restore, stable UUIDs, and rollback after a deliberate UUID uniqueness failure.
- Hardened payment replay so a reused idempotency key must match both the original order and branch. Added explicit amount precision/range validation for `NUMERIC(12,2)`.
- Added branch membership validation for a supplied payment actor.
- Reworked reversal-limit enforcement to use `ops.payment_reversal_totals` and a conditional row update. Competing reversals for one payment now serialize on the same accumulator row; a failed limit check raises and rolls back the reversal event.
- Added PostgreSQL before/after insert triggers to assign UUIDs and register newly inserted syncable business rows as `CLOUD_ONLY`. This preserves current integer-ID insert paths after `sync_id` became NOT NULL.
- Audited SQLite connection sites. The tool uses `contextlib.closing` for inventory, backup source/destination/integrity, backfill and reconciliation; corrected the rollback comment to describe `close()` behavior. Tests now close fixture and assertion connections deterministically.
- Added Windows handle-release checks after inventory, backup, successful and failed backfill, and failed reconciliation. Tests rename database and backup files before temporary-directory cleanup.
- Added migration contract assertions for new-row UUID generation and identity-map registration.
- Added tests for the new repository checks and the migration contract. The final V34 SQL and concurrency behavior were additionally validated against a local disposable PostgreSQL instance in this finalization pass.

## Python maintenance tooling

Python **3.12.10** is installed at `C:\Users\win 10\AppData\Local\Programs\Python\Python312\python.exe`. The shell's `python` command still resolves to the Windows Store execution alias, so tests were invoked with the installed interpreter's full path. The maintenance tool uses only the Python standard library.

```powershell
& 'C:\Users\win 10\AppData\Local\Programs\Python\Python312\python.exe' --version
& 'C:\Users\win 10\AppData\Local\Programs\Python\Python312\python.exe' -m unittest discover -s tools/m28 -p 'test_*.py' -v
```

**Latest rerun result: 6 tests passed, 0 failures, 0 errors, in 1.960 seconds.** The user-reported run also passed all 6 tests in 3.349 seconds. The suite covers read-only inventory, the `--apply` guard, additive/stable UUID backfill, verified backup, interrupted backfill rollback, reviewed reconciliation, failed-reconciliation rollback, and rename-based SQLite handle release. The original defect was bare `with sqlite3.connect(...)` usage: the connection context manager manages transactions but does not close the connection. A remaining bare connection in the reconciliation test kept `edge.sqlite` locked during `TemporaryDirectory` cleanup. Test connections now use `closing_sqlite`, with explicit commits where writes must persist. A sandboxed rerun could not create SQLite files in its temp directory; the same full suite was rerun outside the filesystem sandbox and passed.

`m28_sqlite.py` has no subprocess or retained cursor handles. Every database connection is closed with `contextlib.closing`; `backup()` closes source and destination before checking the copied database. Failed backfill/reconciliation close with transactions active, so SQLite rolls them back. Regression tests verify rollback and later file rename.

## SQLite migration validation

The final Flutter suite passed **47 tests**, including these M28 scenarios:

- A legacy-style SQLite database with existing orders, order items, and payments retains IDs, rows, payment sum, and foreign-key validity after UUID backfill.
- Applying the migration twice preserves generated UUID identity and version state.
- A new business update increments the mutable-row version; changing a stable UUID outside reconciliation is rejected.
- A file-backed pre-upgrade database produces a backup that passes `integrity_check`; copying that backup back restores the original business rows and passes integrity checks.
- A duplicate UUID causes migration failure inside a transaction; added schema changes roll back and the pre-existing order rows remain.

This is a representative synthetic legacy fixture, not a copy of a deployed Edge database. Python tooling tests and restore of an actual application-created database file remain unverified.

## PostgreSQL migration validation

The final V34 was executed with PostgreSQL 18.6 against local disposable instances. One database was populated with synthetic tenant, branch, table, category, customer, product, two orders, order items, KOT, order status log, booking, and inventory rows before V34. No production database was contacted.

- V34 applied successfully in one transaction to the populated pre-V34 database. All seeded order, item, KOT and linked business rows remained present. Existing integer IDs and foreign keys were retained.
- UUIDs were backfilled for all allowlisted rows, were unique per entity, and every identity-map entry resolved to the corresponding cloud row. Mappings remain `UNRECONCILED`; matching numeric IDs are not treated as cross-database matches.
- A rollback-scoped update probe confirmed an order's version moved from 0 to 1 while its `sync_id` stayed stable. The probe itself was rolled back.
- The pre-existing paid order retained its `Paid` status and amount, and received **zero** payment events. This confirms the migration does not fabricate historical ledger entries from order status.
- An intentionally interrupted V34 run on a separate disposable V1–V33 database failed after executing the migration body. `psql --single-transaction` rolled back all changes: `orders.sync_id` and the `ops` schema were absent afterward. The unmodified V34 then applied successfully to that database.
- `pg_dump`/`pg_restore` to a separate disposable database restored the populated fixture, UUID mappings, payment events and reversal accumulator. Restored counts matched the source fixture.
- A clean Spring Boot startup with Flyway enabled validated and applied all 33 versioned migrations through V34 to an empty disposable PostgreSQL database. Flyway recorded V34 as successful. Flyway warned PostgreSQL 18 is newer than its tested support (16).
- The Spring `PaymentLedgerRepositoryTest` suite ran against a separately migrated disposable PostgreSQL database: **3 passed**. Its first run exposed that application inserts omit the newly required `sync_id`; the added before/after triggers fixed that failure. The passing run exercised normal branch/order inserts and payment/reversal repository behavior on PostgreSQL.

An interrupted migration was forced both through PostgreSQL `psql --single-transaction` and through Flyway itself. Flyway logged that failed V34 changes were rolled back; `repair()` found no failed history row, and a retry applied V34 successfully. The probe verified `orders.sync_id` was absent after the failed attempt and V34 was successful after retry.

### Latest populated V33-to-V34 and Flyway validation

- Created a fresh local disposable database, applied V1–V33 through the Spring Boot/Flyway path, then inserted representative legacy data before applying V34: a branch/table, category, customer, product, paid and unpaid orders, order items, KOT, status log, booking, and inventory. Fixture integer IDs were in the 990001 range. The database was never connected to production or Neon.
- Flyway validated the existing migration set and applied V34 successfully, advancing the schema from v33 to v34. A later Spring Boot startup reported v34 up to date, validated the migration set again, and recorded the same V34 checksum (`553056649`); no checksum mismatch or repair was needed.
- Existing fixture orders, items, branch and foreign-key links remained present. Fixture order totals summed to 220.00 and their item totals also summed to 220.00. PostgreSQL reported zero unvalidated foreign keys, zero missing UUIDs on the inspected rows, and zero mismatches between the branch/order/item UUIDs and their `ops.sync_identity_map` records.
- An update probe inside a transaction verified an order's version increments while `sync_id` remains stable; the probe was rolled back. The fixture's already-paid order stayed `Paid` and had zero `payment_events`, confirming no historical payment event was synthesized.
- A direct two-transaction PostgreSQL reversal race requested 60.00 twice against a 100.00 payment. One transaction committed and the other was rejected; the database retained one reversal for 60.00. Two concurrent payment inserts with distinct UUIDs and the same tenant idempotency key resulted in one inserted event and one `ON CONFLICT` no-op; one event remained for the key.
- Separate inserts with a mismatched branch and a mismatched tenant were both rejected by `ops.validate_payment_event`; no invalid-scope rows persisted. `PaymentLedgerRepositoryTest` then passed against the same real PostgreSQL database: **3 tests, 0 failures/errors**. The follow-up Flyway startup validated the applied schema and reported v34 up to date.
- The repository's Flyway version warns that PostgreSQL 18.6 is newer than the latest version it reports as tested (16). This is a compatibility warning, not a migration failure; deployment PostgreSQL support still needs confirmation.

## Payment concurrency and idempotency results

- On PostgreSQL, two concurrent reversal inserts each requested 60.00 against one 100.00 payment. One committed; the other was rejected by the accumulator trigger. The resulting reversal event count was 1 and the recorded reversed amount was 60.00.
- Two concurrent payment inserts with distinct event UUIDs and the same tenant idempotency key resulted in one inserted row and one no-op under the unique idempotency constraint. Exactly one row remained for that key.
- The ledger accepted a payment and preserved the existing order scope. The database enforces event immutability, order/tenant/branch matching, positive precision-limited amounts, reversal linkage, and idempotency uniqueness.
- Repository tests passed on H2 for accepted payment events, exact payment retries, duplicate event prevention, changed-request rejection, branch mismatch rejection, reversal events, and exact reversal replay. The full backend suite passed **108 tests** after the migration-contract assertion update.
- `PaymentLedgerRepositoryTest` also passed all **3 tests** through Spring/JDBC on PostgreSQL 18.6 using the V34 schema.

## Backup and recovery validation

- SQLite file backup, integrity check, restore, and transactional migration rollback passed the Flutter fixture tests described above.
- PostgreSQL custom-format `pg_dump` and `pg_restore` succeeded between separate disposable databases. Fixture business rows, payment event amounts, reversal totals, and identity mappings were checked after restore.
- An intentionally interrupted single-transaction V34 run rolled back its DDL and data changes, and a subsequent clean run succeeded.
- Flyway failure recovery was also exercised with a disposable copy of V34 containing an intentional SQL error: DDL rolled back, Flyway repair completed, and the unmodified V34 retry succeeded.

## Security and device policies

| Proposed policy | Existing implementation evidence | Finalization result |
|---|---|---|
| Only an authorized Edge host synchronizes branch operational data | V34 has disabled sync state and tenant/branch-bound registration metadata. There is no sync API/worker yet. | Not enforced yet; keep sync disabled. |
| One Edge identity is bound to one tenant and branch | Cloud sync-state schema has composite tenant/branch constraints and is inactive. Local state is `UNBOUND`; no server-issued Edge identity is registered. | Schema foundation only. Binding lifecycle remains to be designed and security-reviewed. |
| LAN clients use authenticated Edge domain operations | Current clients call the Shelf `/api/db/*` SQL proxy on port 8082. The client may attach a user Bearer token, but the Shelf handlers do not validate it; raw SQL endpoints remain available. LAN traffic is HTTP in the current client. | **Critical blocker.** The proposed requirement is not met. Replacing the proxy with authenticated domain operations is Phase 3 work and was not started. |
| Registered device credentials and JWT authorization | Spring already has user JWTs, refresh tokens, auth-device records, and one-time device activation codes. These are not an Edge host sync credential or a proof that Shelf authorizes callers. | Security approval needed for issuer/audience/claims, Edge key storage, access lifetime, refresh/rotation, revocation, offline validity, and LAN/cloud TLS. |
| INR default with explicit branch currency | The repository uses INR in some paths, but there is no authoritative branch-level currency field/configuration. The ledger accepts an explicit currency and does not infer it. | Product approval needed on which branches default to INR, international branch behavior, configuration ownership, and treatment of legacy SQLite payments with unknown currency. |
| Immutable, idempotent payments and reversals | New PostgreSQL ledger schema and repository implement append-only events and idempotency; PostgreSQL tests confirmed database constraints, a duplicate-key race, concurrent reversal limits, and all three Spring repository tests. Existing endpoints still update order payment fields. | Database foundation validated. Production write paths are not switched to the ledger; payment semantics still need approval. |
| Never fabricate historical payment events from order status | No migration converts cloud order status into payment events. | Preserved. Historical payment reconciliation needs an approved accounting policy. |
| Verified UUID/ownership reconciliation | Mapping registry uses `UNRECONCILED`; tool requires reviewed mappings and guards changed rows. All six Python tooling tests passed. | Tool behavior verified on synthetic databases; source ownership still needs evidence from real Edge files. |
| Backup restore needs a server-authorized epoch reset | Epoch columns exist with defaults, but no authorized reset API/procedure exists. | Not implemented; security/operations approval and recovery runbook required before sync. |
| Flutter Web is online-only and cannot bypass Edge operational ownership | `DatabaseHelper` returns `NetworkDatabase` for Web, using the configured Shelf host on port 8082; application flows also use the Spring API. No single enforced routing policy exists. | Product/architecture decision required: define which Web operations are allowed and route branch operational mutations through the authorized Edge domain boundary. |

### Recommended policy baseline for approval

These are the proposed Phase 3 operating rules. They are not implemented by Phase 2 and require named owner approval before sync work:

1. **Edge host and branch binding:** Register each Edge host with exactly one tenant and one branch. Only that registered host may synchronize operational data for the branch. A rebind must revoke the former credentials, require a new server-authorized binding, and advance the sync epoch. LAN clients must submit authenticated domain operations to that host; raw SQL proxy access is not an accepted sync path.
2. **Device authentication:** Use backend-issued device credentials and JWTs for Edge-to-cloud authorization. Bind `sub` to the registered device UUID and include tenant, branch, sync epoch, issuer, audience, and narrow scopes. Require TLS for LAN and cloud traffic. As a starting point for Security review, use 15-minute access tokens and rotating refresh credentials held in the host OS-protected secret store; revoke on compromise, de-registration, or branch rebind. Offline refresh/revocation behavior must be specified before enabling sync.
3. **Currency:** Configure an explicit ISO 4217 currency on each branch. Default new Indian branches to INR only where branch geography is verified; reject a payment if its branch currency is unknown. Do not silently assign INR to legacy SQLite payment rows whose currency was not recorded.
4. **Payment history:** Keep payment and reversal events append-only and idempotent. Reuse of a key with the same payload returns the original result; reuse with a changed payload is rejected. Never derive historical ledger events from an order's current payment status. Finance may reconcile only from verified original payment records; unresolved history remains unreconciled.
5. **Backup restore and epochs:** A restored Edge host remains unable to push until the server authorizes a one-use epoch reset bound to its device, tenant, and branch. The reset invalidates stale credentials/cursors and requires a controlled full resynchronization before normal operation resumes.
6. **Flutter Web:** Keep Web online-only. Branch operational writes must pass through the registered Edge authorization boundary; do not allow a Web client to use the Shelf SQL proxy or write around branch ownership. Product must define which Web workflows may create or update operational data.

Security approval is required for device credential lifecycle, JWT claims/lifetimes, TLS, rebind, revocation, and restore authorization. Product approval is required for branch currency defaults and Web workflow ownership. Finance approval is required for legacy payment reconciliation. Operations approval is required for the restore/recovery runbook. Data owners must verify actual Edge-to-cloud ownership mappings from real Edge inventories before reconciliation.

### Required product and security approvals

1. Approve the Edge host's identity model, permitted host/device classes, one-branch binding, rebind/revocation process, and restore epoch reset authority.
2. Approve how registered Edge credentials are issued, stored, expired, rotated, revoked, and represented in JWT claims; require authenticated, encrypted LAN domain calls.
3. Confirm branch currency configuration and INR defaults; define whether every current branch qualifies as Indian and how existing local payments without a known currency are handled.
4. Approve a historical payment reconciliation policy that explicitly excludes synthetic events derived only from order payment status.
5. Decide whether Web can create branch operational data and, if so, require the same Edge ownership/security boundary.
6. Approve source ownership and mapping decisions only after inventories of actual Edge files and cloud branch data.

## Files created and modified

**Created**

- `M28_PHASE_2_FINAL_VALIDATION_REPORT.md`

**Modified during finalization**

- `M28_PHASE_2_IMPLEMENTATION_REPORT.md` — recorded fixes and latest Flutter test count.
- `frontend/lib/core/database/database_helper.dart` — atomic post-open M28 migration pass.
- `frontend/test/m28_identity_migration_test.dart` — populated legacy fixture, backup/restore, foreign-key, and interrupted-transaction tests.
- `backend/src/main/java/com/dinemaster/orders/repository/PaymentLedgerRepository.java` — branch-bound replay checks and amount precision validation.
- `backend/src/main/resources/db/migration/V34__m28_identity_payment_foundation.sql` — atomic reversal-total accumulator, actor branch-membership validation, and UUID registration triggers for inserts.
- `backend/src/test/java/com/dinemaster/orders/repository/PaymentLedgerRepositoryTest.java` — branch mismatch and reversal replay coverage.
- `backend/src/test/java/com/dinemaster/sync/M28MigrationContractTest.java` — reversal accumulator and insert-identity trigger contract assertions.
- `tools/m28/m28_sqlite.py` — documented deterministic connection close and implicit rollback behavior; connections already use `closing()` on normal and error paths.
- `tools/m28/test_m28_sqlite.py` — deterministic connection cleanup and Windows file-handle regression tests; all six pass.

No automatic sync, sync feature flag, production migration, or Phase 3 component was added.

## Remaining blockers

- Flyway clean startup, failed-migration repair/retry and Spring payment repository tests passed on disposable PostgreSQL. PostgreSQL 18.6 is newer than the version this Flyway release reports as tested (16); verify the actual deployment PostgreSQL version against the supported Flyway release before rollout.
- Actual production-shaped SQLite inventory/reconciliation has not been performed.
- The current LAN raw SQL proxy is not authenticated by the Shelf handlers; this conflicts with the required Edge domain security boundary.
- Device credential issuance and JWT settings, branch currency and Web write routing, historical payment accounting, backup epoch reset, and real-file ownership reconciliation require the approvals described above.

## Final Phase 2 acceptance checklist

- [x] No production database accessed or modified.
- [x] No Phase 3 or automatic synchronization implemented; sync remains disabled.
- [x] SQLite migration keeps integer IDs/FKs and has a verified backup path; representative populated fixture, UUID stability, payment total, foreign-key check, backup restore, and transaction rollback tests passed.
- [x] Latest full Flutter suite passed: 47 tests.
- [x] Latest full backend suite passed: 108 tests, 0 failures, 0 errors; focused real-PostgreSQL payment repository suite passed: 3 tests.
- [x] Latest Python 3.12.10 maintenance-tool suite passed: 6 tests, 0 failures, 0 errors, including Windows handle cleanup and rollback.
- [x] Latest V33-to-V34 Flyway migration executed against populated disposable PostgreSQL data.
- [x] PostgreSQL migration data reconciliation, transaction rollback/recovery, and backup restore tested.
- [x] PostgreSQL duplicate-idempotency and concurrent-reversal behavior tested against database constraints/triggers.
- [x] Clean Flyway startup applied V1–V34; Spring payment repository tests passed on PostgreSQL.
- [x] Interrupted Flyway V34 rolled back; repair and retry succeeded on a disposable PostgreSQL database.
- [ ] Edge/LAN/Web security and currency policies approved and enforced before Phase 3 sync work.

## Phase 3 readiness

**NOT READY.** The latest Python (6), Flutter (47), and backend (108) suites passed. The populated V33-to-V34 PostgreSQL migration, checksum validation, scope isolation, UUID stability, foreign-key and financial-total probes, real-transaction duplicate/reversal races, prior interrupted-migration recovery, and backup restore also passed on disposable databases. Phase 3 is blocked by the unauthenticated LAN SQL proxy, missing Edge credential and branch-binding enforcement, policy approvals for currency/Web/history/epoch recovery, absent ownership evidence from actual Edge files, and confirmation of the deployment PostgreSQL version against Flyway support. No Phase 3 work or automatic synchronization has started.
