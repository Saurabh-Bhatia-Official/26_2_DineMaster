# M28 MVP Implementation and Test Report

Date: 25 November 2026 (final validation update; original MVP implementation: 24 November 2026)  
Scope: isolated development MVP on branch `dev-hrushikesh1`  
Production/Neon access: none  
Automatic synchronization default: disabled

## Current verification status

**MVP NOT VERIFIED end to end.** The Flutter application did not complete an order-to-sync run against Spring Boot and PostgreSQL. This report does not mark the MVP verified. A live HTTPS API smoke run using a Java client passed, but it did not exercise Flutter’s enrollment, local SQLite workflow, outbox, or automatic retry path.

The single-Edge-host synchronization MVP is implemented for the limited data set below and remains suitable for isolated development, **not production rollout**. The validation results and blockers recorded on 25 November are in the final section below.

Phase 2 and existing Phase 3A work were retained. V34 and V35 were not edited. Migrations V36 and V37 were added additively.

## MVP behavior

### Edge host and tenant/branch binding

- Existing activation-code issuance remains protected by the cloud staff authentication and `edge_host:activate` permission, with branch assignment and tenant checks.
- Activation codes are single-use, expire after 15 minutes, and are stored as hashes. Activation consumption validates RSA-2048-or-stronger proof of possession and the activation nonce.
- PostgreSQL's partial unique index enforces one active Edge host per tenant/branch.
- Edge host access tokens use RS256, a distinct issuer and audience, a 15-minute expiry, and host, tenant, branch, epoch, subject, type, and sync-scope claims. Validation checks the signature, RS256 algorithm, issuer, audience, expiry, scope, active host row, tenant, branch, and epoch.
- Refresh values are random, stored as SHA-256 hashes, rotate on use, expire within seven days, and reuse revokes their family. Refresh requests are rate limited.
- Cloud user JWTs and host credentials remain separate. Signing keys are required through `EDGE_JWT_PRIVATE_KEY_PEM` and `EDGE_JWT_PUBLIC_KEY_PEM`; there is no HMAC or base64 fallback.
- Flutter requires an HTTPS API URL and uses the platform's normal TLS certificate verification. RSA host material and bearer credentials use OS-backed secure storage. The local enrollment flow compares the cloud branch UUID with the already reconciled local branch UUID before consuming activation.
- The local `sync_device_state.tenant_sync_id` slot stores the authenticated cloud tenant ID as a scope string because the existing tenant schema has no UUID sidecar. It is not used as a cross-database row mapping and does not infer identity from matching integer IDs.

### Local writes and automatic sync

- SQLite `AFTER INSERT` and business-field update triggers enqueue `orders` and `order_items` changes in `sync_outbox` in the same SQLite transaction as the business write. Each event has a stable UUID change ID and entity UUID.
- The durable outbox supports leases, retries with capped exponential backoff, and acknowledgement/conflict state. A stale in-flight lease becomes retryable after five minutes. Closing and reopening the database preserves pending changes.
- `SyncEngine` runs on non-Web startup, connectivity restoration, and a five-minute timer. A single-job guard prevents overlapping runs. Failed transport requests remain in the outbox for retry.
- Flutter Web remains outside this Edge sync path.
- The app has a small dashboard status indicator for disabled/registration/offline/syncing/current/auth-required/error states. The authenticated cloud status endpoint reports host scope, epoch, cursor, and receipt counts.

### Cloud APIs and data handling

Authenticated Edge host tokens are required by:

- `POST /api/v1/sync/push`
- `GET /api/v1/sync/pull?stream=menu&cursor=...&limit=...`
- `POST /api/v1/sync/pull/ack`
- `GET /api/v1/sync/status`

Push accepts only `orders` and `order_items` UPSERT changes. The server derives tenant and branch from the validated host, ignores client-supplied ownership IDs, checks product UUID/version/current price, and requires the parent order to exist before accepting an item. Payments and payment status are not synchronized. Cloud order totals are calculated from accepted order-item prices and tax; client-supplied totals are not trusted. The order number is derived from the order UUID within the existing 40-character limit.

Each push batch runs transactionally. `ops.edge_processed_changes` stores the response and a SHA-256 fingerprint of canonicalized request content. Replaying the same change ID and same content returns the stored receipt; reusing it with different content is rejected. V37 makes the fingerprint column additive; older receipts without a fingerprint fail closed on replay.

Pull is limited to tenant-visible categories and products. Cloud cursors are durable and advance only after the local SQLite catalog transaction commits and the client acknowledges the issued cursor. Local catalog UUID mappings retain cloud and local integer IDs as separate values.

## Database migrations and validation

The isolated disposable database used PostgreSQL 18.6 on loopback, database `m28_mvp_validation`, port 55439. The test cluster was stopped after validation. No production or Neon database was contacted.

| Migration | Result |
|---|---|
| V34 identity/payment foundation | Applied successfully; unchanged |
| V35 Edge host registration | Applied successfully to a fresh disposable database; unchanged |
| V36 refresh-token, Edge cursor, and processed-change tables | Applied successfully |
| V37 request fingerprint column | Applied successfully |

Flyway recorded V34-V37 as successful. The full Flyway history was applied to the fresh disposable database, and Spring Boot started against it with Hibernate `ddl-auto=validate`. Flyway emitted its existing supported-PostgreSQL-version warning because the validation server was PostgreSQL 18.6; migration execution and application startup succeeded.

V36 adds `ops.edge_host_refresh_families`, `ops.edge_host_refresh_tokens`, `ops.edge_sync_cursors`, and `ops.edge_processed_changes`. V37 adds nullable `request_hash` so existing receipt rows are not fabricated or rewritten. No table, column, or integer primary key was deleted or replaced.

## Test results

| Validation | Actual result |
|---|---|
| Backend complete Maven suite | 117 discovered; 116 passed, 0 failures, 0 errors, 1 PostgreSQL integration test skipped by default gate |
| PostgreSQL MVP integration | 1 passed against disposable PostgreSQL 18.6 with synthetic tenant, branch, catalog, host, order, and item fixtures |
| Flutter complete suite | 56 passed, 0 failures |
| Dart analysis of changed M28 Flutter files and dashboard | No issues found |
| Python M28 maintenance suite | 6 passed, 0 failures, 0 errors |
| V34-V37 migration history and application startup | All four migration records successful; JPA validation/startup succeeded |

The PostgreSQL integration test exercised host credential issuance and access validation, order-before-item push, duplicate replay, rejection of a changed payload with the same change ID, cloud-side item/financial total calculation, UUID identity mappings, category/product pull, repeated cursor acknowledgement, status, and fixture cleanup. It invokes the service layer against real PostgreSQL; it is **not** an HTTP or Flutter transport test.

The Flutter tests cover additive SQLite identity/outbox writes, rollback and backup cases, RSA activation proof binding, outbox retry UUID preservation, and pending-change survival across database close/reopen. The Python suite covers the existing inventory, backup, backfill, reconciliation, rollback, and Windows resource cleanup behavior.

Validation also caught and fixed four implementation defects before the final runs: RSA DER sequence construction in the Flutter identity helper, missing tenant scope in the local `BOUND` device row, an `order_no` value longer than PostgreSQL's 40-character limit, and trust in client-supplied order totals. The final flow derives totals from accepted product prices/tax and binds idempotency receipts to request content.

## Files added or changed for this MVP

### Backend

- Added `backend/src/main/java/com/dinemaster/auth/service/EdgeHostCredentialService.java` and `backend/src/main/java/com/dinemaster/auth/controller/EdgeHostCredentialController.java` for host access/refresh credentials.
- Extended `EdgeHostEnrollmentController.java` to issue scoped credentials on activation consumption.
- Added `backend/src/main/java/com/dinemaster/sync/` push, pull, cursor acknowledgement, and status APIs/services/DTOs.
- Added V36 and V37 migrations under `backend/src/main/resources/db/migration/`.
- Added credential, migration-contract, and gated real-PostgreSQL integration tests under `backend/src/test/java/com/dinemaster/`.
- Updated `backend/src/main/resources/application.yml`; Edge control plane and sync remain false by default.

### Flutter

- Added `frontend/lib/core/database/m28_sqlite_migration.dart`, `frontend/lib/repositories/outbox_repository.dart`, `frontend/lib/services/sync_engine.dart`, and Edge host credential/identity/provisioning modules.
- Updated `frontend/lib/core/database/database_helper.dart`, `frontend/lib/main.dart`, and `frontend/lib/screens/dashboard_screen.dart` for migration, startup, and status.
- Added `pointycastle` for RSA proof generation and tests for identity, outbox, and persistence.

Previously existing Phase 2/3A changes and planning documents were left in place. This report is the only new consolidated MVP report.

## Known limits and remaining work

### Explicitly deferred from the fast-track MVP

- Payment synchronization, payment/reversal events, inventory synchronization, KOT/KDS, reservations, customer sync, deletions/tombstones, multi-device LAN workflows, host replacement/rebind, backup epoch reset, and advanced conflict resolution/UI.
- Authenticated WSS, LAN certificate provisioning/trust pairing, and the remaining domain APIs replacing raw SQL are not implemented. Existing unrestricted raw SQL access remains disabled; no fallback was restored.
- User offline signed grants and offline credential renewal are not part of this sync implementation. Local business workflows continue using existing local behavior; sync itself requires reachable HTTPS cloud APIs.
- Product/category deletion propagation is not implemented. Order table, waiter, and customer relationships are not reconstructed in cloud by this selected-order MVP.
- An item whose product version/price changed is left in conflict for operator follow-up; no conflict resolution interface exists.

### Verification and rollout gates

- No complete HTTP-level run from cloud staff activation through Flutter `SyncEngine` to PostgreSQL was executed. The cloud sync service was validated at service level with real PostgreSQL; Flutter local persistence/retry was validated separately.
- Real Wi-Fi loss/reconnect, app process restart with a live host credential, TLS handshake/certificate rejection, concurrent activation race, concurrent refresh replay, and concurrent change submission were not exercised end to end.
- There is no immediate outbox-insert callback; new records are picked up at startup, when connectivity returns, or at the five-minute poll. The outbox remains durable during that delay.
- Cloud JWT signing-key custody/rotation, host revocation and replacement endpoints, local enrollment compensation if secure storage fails after server activation, epoch reset, certificate provisioning, and monitoring/alerting need production design and operational ownership.
- Production tenant/branch UUID reconciliation, verified data ownership, device credential issuance, deployment compatibility, security/product/finance approvals, and controlled rollout authorization remain release gates. No human approvals are inferred here.
- Existing Flyway support warning for PostgreSQL 18.6 should be resolved by validating against the project's supported production PostgreSQL version before rollout.

## Feature flags and safe enablement

- Backend `EDGE_CONTROL_PLANE_ENABLED` defaults to `false`.
- Backend `M28_SYNC_ENABLED` defaults to `false`.
- Flutter sync preference is absent/false by default. It becomes enabled only after explicit Edge host activation succeeds.
- Flutter Web does not start the Edge sync engine.

Do not enable these flags in production based solely on this MVP report. Production rollout requires the verification, reconciliation, security, and operational gates above.

## Final end-to-end validation update — 25 November 2026

### Outcome

The Flutter-to-Spring-to-PostgreSQL workflow is **not verified**. The backend HTTPS APIs passed live checks against a disposable PostgreSQL database, but no complete Flutter application order/outbox/push run completed. In particular, the local waiter screen, SQLite order and outbox, automatic resend after a real connectivity loss, and cloud row creation from that Flutter order were not proven together.

### Isolated development environment

- Created a fresh PostgreSQL 18.6 cluster in `%TEMP%\DineMaster-M28-MVP-20261125`, bound to loopback port `55441`, with database `m28_mvp_e2e`. The cluster and database were disposable. No production or Neon endpoints were used.
- Started Spring Boot 3.3.4 on HTTPS port `8443`, with `EDGE_CONTROL_PLANE_ENABLED=true` and `M28_SYNC_ENABLED=true` for that process only. Flyway applied V34–V37 to the disposable database. The test flags were not written into defaults or enabled for production.
- Generated a two-day `localhost` certificate with `localhost` and `127.0.0.1` SANs, plus separate disposable RSA signing keys. The live Java API check used a dedicated Java truststore containing only the generated certificate; TLS chain and hostname verification remained on. The certificate was not added to the Windows Root store, so the normal Flutter Windows client was not proven against this certificate.
- Seeded synthetic tenant/branch, owner, category, products in the authorized branch, another branch, and another tenant. Local integer IDs differed from the cloud branch ID; the API checks used the explicit UUID mapping.

### Live HTTPS API checks (Java client, not Flutter)

All of these requests reached the running Spring service over verified HTTPS:

- Synthetic cloud staff login: HTTP 200.
- Authorized activation-code issue and RSA proof-of-possession host enrollment: HTTP 201; activation replay: HTTP 401.
- Unauthenticated sync status: HTTP 401.
- Bound host menu pull: HTTP 200. The response included the bound branch's product and excluded the seeded other-branch and other-tenant products. Cursor acknowledgement: HTTP 204.
- Synthetic order followed by its order item: accepted by the authenticated push endpoint. Replaying the same change IDs returned accepted receipts; reusing a change ID with altered payload returned `IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD`.
- Refresh-token rotation issued a different host JWT; the rotated token was accepted by sync status.
- PostgreSQL query after the live API run showed one order and one order item for tenant 1 / branch 2, order total `50.00`, item total `50.00`, and two processed-change receipts. This evidence proves the Spring HTTP API path only; the payload was created by the Java smoke client, not by the Flutter workflow.

The focused real-PostgreSQL repository test also passed after the API checks: `EdgeSyncPostgresMvpIntegrationTest`, 1 test, 0 failures/errors/skips. The complete backend suite passed: 117 tests, 0 failures, 0 errors, 1 gated PostgreSQL test skipped by default. The Python M28 suite passed: 6 tests, 0 failures/errors. The Python run required host permissions because the sandbox prevented SQLite from creating its temporary fixture databases; rerunning with host permissions passed without changing the test assertions.

The latest complete Flutter suite result available before this validation attempt was 56 passed. It was not rerun after adding the conditional live-flow test. A `flutter drive ... -d windows` attempt stopped before app launch with `Unable to find suitable Visual Studio toolchain`; the machine has no Windows C++ desktop build tools. A test-runner version of the Flutter flow was prepared in `frontend/test/m28_live_mvp_e2e_test.dart`, but its final run after the test-fixture adjustment was not executed because the required command approval was rejected. It is conditionally skipped unless `M28_E2E_CERT_PATH` is provided. Do not count it as passed.

### Exact local setup commands and environment

The disposable PostgreSQL setup used these commands (the foreground `postgres.exe` process was used because `pg_ctl start` could not create its restricted child process in this environment):

```powershell
$pgRoot = Join-Path $env:TEMP 'DineMaster-M28-MVP-20261125'
& 'C:\Program Files\PostgreSQL\18\bin\initdb.exe' -D (Join-Path $pgRoot 'pgdata') -U postgres --auth=trust --no-locale -E UTF8
& 'C:\Program Files\PostgreSQL\18\bin\postgres.exe' -D (Join-Path $pgRoot 'pgdata') -h 127.0.0.1 -p 55441
& 'C:\Program Files\PostgreSQL\18\bin\createdb.exe' -h 127.0.0.1 -p 55441 -U postgres m28_mvp_e2e
```

The temporary TLS keystore was generated with Java `keytool` using RSA-2048, PKCS12, a two-day validity, and `SAN=dns:localhost,ip:127.0.0.1`; its certificate was exported to PEM. A separate RSA-2048 PKCS12 keypair was generated for Edge JWT signing and exported to PKCS8 private-key PEM and X.509 public-key PEM. No key or certificate values were committed.

Backend process environment variable names used:

```text
JAVA_HOME
DB_HOST=127.0.0.1
DB_PORT=55441
DB_NAME=m28_mvp_e2e
DB_USERNAME=postgres
DB_PASSWORD
EDGE_CONTROL_PLANE_ENABLED=true
M28_SYNC_ENABLED=true
EDGE_JWT_PRIVATE_KEY_PEM
EDGE_JWT_PUBLIC_KEY_PEM
SERVER_PORT=8443
SERVER_SSL_ENABLED=true
SERVER_SSL_KEY_STORE
SERVER_SSL_KEY_STORE_PASSWORD
SERVER_SSL_KEY_STORE_TYPE=PKCS12
SERVER_SSL_KEY_ALIAS=m28-local
```

The backend was launched from `backend` with `.\mvnw.cmd -q spring-boot:run`. The passing complete backend regression command was `.\mvnw.cmd test` with the disposable `DB_*` variables and both M28 flags set to `false`. The focused PostgreSQL command was `.\mvnw.cmd '-Dm28.pg.integration=true' '-Dtest=EdgeSyncPostgresMvpIntegrationTest' test`. The passing Python command was `python.exe -m unittest discover -s tools/m28 -p 'test_*.py' -v` using Python 3.12.10.

### Remaining verification gates

- Install the Visual Studio C++ desktop workload and MSVC toolchain, or provide another supported Flutter Edge target, then launch the native Flutter app and run the full live harness.
- Establish a normally trusted development HTTPS certificate for the Flutter process; do not disable TLS validation. Then verify activation, catalog pull, actual waiter UI order, SQLite/outbox rows, automatic push, and PostgreSQL order/item rows in one run.
- Run actual connectivity interruption/reconnection, automatic retry, restart with pending outbox, token refresh through Flutter, duplicate replay through Flutter, and invalid-certificate rejection through Flutter.
- The signed offline user-grant flow remains unimplemented. A user already authenticated online can use the local workflow in principle, but no offline order was validated in this run. Cold-start offline login remains fail-closed; do not add a local-password or unsigned-token fallback.
- Rerun the complete Flutter suite after the live harness is finished. Existing 56-pass results predate the new conditional harness.

Until those checks pass, the status remains **MVP NOT VERIFIED**. Production readiness remains separate and is still blocked by the reconciliation, credential custody, rollout, and owner-approval gates above.

## Focused Flutter live verification update — 25 November 2026

### Environment and execution

- Used a new disposable PostgreSQL 18.6 database `m28_mvp_run2` on loopback port `55442` and Spring Boot 3.3.4 over HTTPS on port `8443`. Flyway applied V34–V37. No production or Neon endpoints were configured or accessed.
- Generated a temporary localhost certificate with `localhost` and `127.0.0.1` SANs and an isolated RSA signing key pair. The test process set `M28_E2E_CERT_PATH` to the generated PEM certificate. The Flutter test `HttpOverrides` trusted that certificate only; explicitly untrusted test clients retain a trust store with no roots. Certificate validation was not disabled.
- Enabled `EDGE_CONTROL_PLANE_ENABLED=true` and `M28_SYNC_ENABLED=true` only in the isolated Spring process. Seeded synthetic data in the default test tenant: branch id `2`, category `M28 E2E Category`, product `M28 E2E Product`, and a branch assignment marked verified. The test login, branch activation, proof-of-possession enrollment, secure host credential storage, and catalog pull ran through Flutter against Spring.

### Verified Flutter results

- Host activation and credential issuance passed in the live harness. The test observed a stored host id and refresh credential.
- PostgreSQL-to-SQLite catalog sync passed after adding the missing additive category columns described below. The local test database contained the synthetic category and one product; the cloud cursor was acknowledged.
- The live harness did **not** pass. After mounting `WaiterOrderScreen`, it could not find `Start New Order`; the expected control was absent, consistent with the screen still loading, and the test did not reach the order form. SQLite had zero order rows, zero order-item rows, and zero order/order-item outbox events; PostgreSQL had no pushed order from this Flutter run.
- Consequently, offline order creation, network reconnection, automatic push, pending-outbox restart recovery, duplicate replay through the Flutter workflow, token refresh through the workflow, invalid-certificate rejection in the final harness stage, and the final PostgreSQL order/item checks were not reached and are not reported as passed. The earlier Java API smoke-test results remain separate evidence and do not satisfy these Flutter gates.

### Directly related fixes made

- `frontend/lib/core/database/m28_sqlite_migration.dart`: add `sort_order` and `is_active` to legacy category schemas when absent. The first live catalog attempt had rolled back because the cloud category payload contained these columns but SQLite did not.
- `frontend/lib/services/edge_host_provisioning.dart`: check HTTP status before decoding success JSON, so an empty error response reports the actual HTTP status instead of a JSON parse error.
- `frontend/test/m28_live_mvp_e2e_test.dart`: correct the conditional test skip type, prevent recursive `HttpOverrides` client construction while retaining an untrusted-certificate test context, and move test database/network operations into the real async zone. The harness also now waits briefly for screen loading and emits UI-stage diagnostics.

### Regression run and current decision

- Complete Flutter suite: `flutter test --no-pub -r expanded` — **56 passed, 1 skipped, 0 failed**. The skipped test is the live-only test because `M28_E2E_CERT_PATH` was unset for the regression run; it is not counted as a pass.
- Focused live command: `$env:M28_E2E_CERT_PATH=<temporary localhost PEM>; flutter test --no-pub test/m28_live_mvp_e2e_test.dart -r expanded` — **failed** at the waiter-screen control lookup (`Start New Order`, 0 widgets found), after successful activation and catalog pull.
- Android target check: `adb` was unavailable. Native Windows UI execution remains unavailable in this environment because the Visual Studio C++ desktop toolchain is not installed.
- **MVP status: NOT VERIFIED.** The required Flutter-created order, transactional outbox, automatic cloud push and PostgreSQL order/item persistence did not pass. The test harness and waiter-screen loading behavior must be resolved before declaring the MVP verified. This remains separate from production release readiness.

## Waiter-screen blocker follow-up — 25 November 2026

### Root cause and targeted fixes

- The original `Start New Order` lookup failed because the local product row returned `prep_time`/`cook_time` as empty text. `ProductModel.fromMap` force-cast those values to `int?`, threw a `TypeError`, and the waiter screen stayed on its loading view while initialization unwound. This was a production parsing defect exposed by the live catalog data.
- `ProductModel.fromMap` now parses optional numeric fields from numbers or numeric strings and treats blank/non-numeric strings as absent. `SyncEngine` also normalizes optional catalog integer fields before inserting them into SQLite, so blank values persist as SQL `NULL`. A focused unit test covers blank and numeric-string values.
- The local order transaction previously awaited `SharedPreferences` while holding SQLite's transaction. The username lookup now happens before the transaction to keep non-database work outside the write lock.
- The live harness now waits on the actual loading indicator while alternating widget pumps with real async event-loop yields. Its polling helper no longer nests `tester.runAsync` when the condition itself performs SQLite work.

### Live Flutter evidence and remaining blockers

- Reused `frontend/test/m28_live_mvp_e2e_test.dart` against a disposable loopback PostgreSQL database and Spring Boot HTTPS server, with a temporary localhost SAN certificate and development signing keys. The database contained only synthetic branch, owner, category, and product fixtures. No production or Neon database was configured or accessed.
- Activation, host credential storage, catalog pull, and cursor acknowledgement passed again. The catalog row showed `prep_time` and `cook_time` stored as SQL `NULL`.
- After the parser fix, one live widget-test run completed waiter-screen initialization and found the `Start New Order` control and synchronized product. The UI test advanced through product and order-type selection. A subsequent attempt reached the automatic-push wait, but the test helper failed with a reentrant `tester.runAsync` call before cloud-push verification. That helper was corrected. Later focused reruns did not complete: one remained in `pumpWidget` after printing that it was mounting the waiter route, and another attempt was interrupted while the isolated services were being restarted. These are not passing results.
- Local order, order-item, and outbox assertions were reached before the reentrant automatic-push wait in the run above. However, no completed live run verified the cloud order/item rows or the full reconnect/restart/duplicate/token-refresh/certificate-rejection sequence. Do not treat those end-to-end gates as passed.
- Native waiter UI execution remains unverified. Android tooling is unavailable (`adb` not found), and Windows desktop execution requires the missing Visual Studio C++ desktop toolchain. Widget-test visibility is not native-device evidence.

### Regression results and files changed in this follow-up

- Full Flutter regression: `flutter test --no-pub --reporter expanded` — **57 passed, 1 skipped, 0 failed**. The skipped case is the live-only test when `M28_E2E_CERT_PATH` is absent; it is not counted as a pass.
- No backend source changed in this follow-up, so no backend test suite was run. Python tooling was unchanged and not rerun.
- Follow-up code/test files: `frontend/lib/models/product_model.dart`, `frontend/lib/services/sync_engine.dart`, `frontend/lib/screens/waiter_order_screen.dart`, `frontend/test/unit_test.dart`, and `frontend/test/m28_live_mvp_e2e_test.dart`. This report is the only documentation file updated.
- **MVP status remains NOT VERIFIED.** Catalog sync and the original waiter-screen defect are resolved, but the existing test runner did not complete the required Flutter-to-Spring-to-PostgreSQL workflow. Offline recovery after restart, cloud order/item persistence, duplicate replay, automatic token refresh, invalid TLS rejection in the live workflow, and native waiter UI remain unverified.
