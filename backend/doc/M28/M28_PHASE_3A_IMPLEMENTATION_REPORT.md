# M28 Phase 3A Prototype Implementation Report

**Status:** Phase 3A is **INCOMPLETE**. This report records an isolated, fail-closed prototype plus a development-only host-enrollment foundation, not a production-ready security boundary.  
**Date:** 2026-11-24  
**Scope:** Local development only. No production/Neon access, automatic synchronization, Phase 3B, or Phase 4.

## Authorization and policy status

DineMaster authorized isolated prototype work using local test databases, synthetic fixtures, and development-only credentials. The proposed policies are technical assumptions for this prototype. Formal Security, Product, Operations, Finance, data-owner, API-owner, and client-owner approvals remain release gates; none are claimed as granted. See `M28_POLICY_APPROVAL_CHECKLIST.md`.

## Implemented

- The Edge Shelf service is disabled by default. Enabling it requires `DINEMASTER_EDGE_SECURITY_ENABLED=true` plus a certificate and private key. It binds only to IPv4 loopback and has no plain-HTTP fallback.
- Removed the Shelf generic SQL route handlers and batch executor. Middleware rejects `/api/db/*` with HTTP `426 LEGACY_SQL_DISABLED` before dispatch.
- `NetworkDatabase` and its HTTP transport implementations no longer transmit arbitrary SQL or table operations and raise `CLIENT_UPGRADE_REQUIRED` locally.
- Removed the public guest order write handler. The security gate returns `426 GUEST_CAPABILITY_REQUIRED` until a scoped guest capability and domain handler exist.
- Added a minimal capabilities response. It explicitly reports that authentication is required, domain operations are unavailable, and automatic synchronization is disabled.
- Removed the unauthenticated WebSocket listener. `SyncService` now dispatches local UI invalidations only; network notifications stay unavailable until authenticated WSS exists.
- Removed local base64 session-token issuance and SharedPreferences bearer-token fallback. Startup clears legacy token preferences; backend tokens are read only from OS-protected Flutter secure storage.
- Offline login fails closed until signed Edge grants exist; the existing local SHA-256 password rows were not migrated or altered.
- Added an opt-in Spring `EDGE_AUTHORITATIVE_MODE` guard. When enabled it rejects mutation requests under orders, kitchen tickets, tables, reservations, inventory, and customers with `503 EDGE_REQUIRED`. It defaults to `false` and does not alter cloud authentication behavior when off.
- Preserved existing integer IDs and business rows. No database migration was added or run.

## Not implemented — Phase 3A remains incomplete

- Host registration now has an additive development foundation described below; revocation, replacement/rebind, live PostgreSQL uniqueness validation, credential issue, and fencing remain incomplete.
- Edge-specific RS256 user grants and host credentials, claim validation, refresh rotation, session/device binding, and credential renewal/revocation endpoints.
- Offline signed-grant validation, trusted-time/clock rollback handling, and Argon2id local verifier migration. Existing offline local login is blocked; local SHA-256 account data remains unchanged.
- Authenticated Edge request middleware for users, devices, roles, permissions, tenant, branch, protocol version, and epoch. The middleware currently implements fail-closed legacy route gates and the public capability response only.
- Certificate provisioning/pairing/renewal and client-side TLS trust integration. The prototype uses caller-supplied certificate/key files and loopback binding only.
- Authenticated WSS; port 8083 is not bound.
- Complete Spring route inventory/classification, guard coverage for future routes, authenticated Edge relay, and server-authorized backup epoch reset/reconciliation.
- End-to-end host enrollment, branch isolation, epoch recovery, TLS handshake, token, revocation, and compatibility tests.

The raw SQL cutover intentionally makes SQL-dependent LAN workflows unavailable until Phase 3B provides authenticated domain operations. The prototype is not ready for branch LAN use.

## Files changed for this prototype

- Backend: `SecurityConfig.java`, `application.yml`; new `EdgeOperationalWriteGuard.java` and `EdgeOperationalWriteGuardTest.java`.
- Flutter: `server_service_io.dart`, `edge_security_gate.dart`, `network_database.dart`, `network_http_io.dart`, `network_http_web.dart`, `sync_service.dart`, `backend_api_client.dart`, `user_repository.dart`, `login_screen.dart`, `splash_screen.dart`; new `m28_edge_security_gate_test.dart`.
- M28 docs: `M28_EDGE_API_CONTRACT.md`, `M28_RAW_SQL_MIGRATION_PLAN.md`, `M28_PHASE_3A_SECURITY_DESIGN.md`, `M28_IMPLEMENTATION_ROADMAP.md`, and `M28_POLICY_APPROVAL_CHECKLIST.md`.
- Reports: this report, `M28_PHASE_3A_SECURITY_TEST_REPORT.md`, and `M28_EDGE_HOST_SETUP_GUIDE.md`.

## Database and API impact

- Database migrations: **none**. No DB was opened or modified by Phase 3A prototype work.
- Removed network APIs: all `/api/db/*` behavior and generic network batches; requests receive `426 LEGACY_SQL_DISABLED` when the prototype server is enabled.
- Guest writes: `POST /api/order` receives `426 GUEST_CAPABILITY_REQUIRED`.
- Capabilities: `GET /api/edge/v1/capabilities` advertises the prototype protocol and disabled feature state.
- Direct cloud operational writes: blocked only when `EDGE_AUTHORITATIVE_MODE=true`; the setting defaults to `false`. The route list is not yet a complete API classification.
- WSS: no endpoint is available.
- Sync: not implemented or enabled.

## Test results

- Backend full suite: **111 passed, 0 failures, 0 errors** across 28 suites (`.\mvnw.cmd -q test`).
- Flutter full suite: **52 passed** (`flutter_tools.snapshot test --reporter compact`, invoked with the installed Dart SDK).
- Python M28 suite: **6 passed, 0 failures, 0 errors**. It passed when run with Python 3.12.10 outside the sandbox; in-sandbox runs could not create SQLite fixture files due Windows access denial.
- New backend guard unit tests: 3 passed as part of the backend suite.
- New Flutter Edge security gate tests: 5 passed as part of the Flutter suite.
- The earlier prototype run had no migration; V35 was added in the continuation below but no PostgreSQL migration or live host registration test was run.

See `M28_PHASE_3A_SECURITY_TEST_REPORT.md` for commands, failure/fix history, and coverage limits.

## Remaining blockers and release gates

- Formal owner approvals remain pending; prototype authorization does not grant production approval.
- Host identity, Edge JWTs, offline grant validation, Argon2id, TLS trust enrollment, WSS, and epoch reset are security-critical unimplemented components.
- The opt-in Spring guard needs a complete route classification and integration coverage before it can be relied on for Edge-authoritative mode.
- Existing client workflows that relied on network SQL or WSS are unavailable until their domain API replacements are implemented.
- No actual Edge ownership reconciliation or production credentials were used.

## Phase 3B readiness

**NOT READY.** Phase 3A authentication and host ownership are not complete, and Phase 3B domain write services have not started. Automatic synchronization remains disabled.

## 2026-11-24 continuation: Edge host enrollment foundation

### Completed

- Added additive Flyway `V35__m28_edge_host_registration.sql`; V34 is unchanged. It adds `ops.edge_host_activations` and `ops.edge_hosts`, tenant/branch composite foreign keys, unique public-key fingerprints, and a partial unique index allowing one active host per tenant/branch.
- Added Spring host activation routes, disabled unless `EDGE_CONTROL_PLANE_ENABLED=true`. Issuance requires `edge_host:activate`, active tenant/branch, tenant match, and verified branch assignment. Activation codes are 256-bit random, stored as SHA-256 hashes, and expire after 15 minutes.
- Consumption locks the activation row, verifies one-use/expiry and rate limit, then validates an RSA proof (minimum 2048 bits) bound to activation UUID, caller nonce, and key fingerprint. The database unique index arbitrates concurrent branch registration. Registration returns `credentialIssued:false` and issues no JWT.
- Added cryptographic proof tests for correct binding, altered activation, malformed signature, and weak RSA key.

### Verification and remaining gaps

- Backend full suite `backend\\mvnw.cmd -q test`: **113 tests passed, 0 failures, 0 errors, 0 skipped**, exit code 0, including `EdgeHostProofTest`. H2 test fixtures were used; the suite does not execute V35 or establish PostgreSQL behavior.
- Flutter full suite: 52 passed.
- Python M28 suite: 6 passed, 0 failures/errors (Python 3.12.10; synthetic temporary SQLite fixtures).
- V35 has not been run against PostgreSQL; persistence concurrency, activation replay, and database-level branch uniqueness are not yet integration-tested.
- Host revocation/rebind, RS256 Edge tokens, refresh rotation/revocation, offline grants/clock rollback, Argon2id migration, TLS pairing, authenticated WSS, complete route classification/guard coverage, and server-authorized epoch recovery remain unimplemented.
- Flutter Shelf does not consume cloud registration; it remains disabled by default and loopback-only. No LAN operations are enabled.

### Continuation files

- Added: backend V35 migration, `EdgeHostActivationRequest`, `EdgeHostProof`, `EdgeHostEnrollmentController`, and `EdgeHostProofTest`.
- Modified: backend `application.yml`; this report; `M28_PHASE_3A_SECURITY_TEST_REPORT.md`; `M28_EDGE_API_CONTRACT.md`; `M28_EDGE_HOST_SETUP_GUIDE.md`; `M28_IMPLEMENTATION_ROADMAP.md`.

**Readiness:** Phase 3A **INCOMPLETE**; Phase 3B technical readiness **NOT READY**; production release readiness **NOT READY**. SQL remains fail-closed and automatic synchronization remains disabled. Existing SQL-dependent LAN business workflows remain unavailable; do not expose the Edge service to clients.
