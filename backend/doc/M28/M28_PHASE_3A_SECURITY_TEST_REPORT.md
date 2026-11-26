# M28 Phase 3A Security Test Report

**Date:** 2026-11-24  
**Scope:** Isolated local prototype tests only. No production/Neon databases, automatic synchronization, Phase 3B, or Phase 4.

## Continuation run (2026-11-24)

- Backend full suite `backend\\mvnw.cmd -q test`: **passed**, exit code 0, including new `EdgeHostProofTest`. H2 test configuration was used. It did not run V35 or PostgreSQL integration tests; exact counts are available under `backend/target/surefire-reports`.
- Flutter full suite using the installed Flutter SDK: **52 passed**.
- Python M28 suite using Python 3.12.10: **6 passed, 0 failures, 0 errors**, 3.127 seconds.
- RSA proof tests accept a valid 2048-bit signature and reject an altered activation binding, malformed proof, and 1024-bit key.

Host registration persistence, activation replay at the database boundary, concurrent host registration, V35 migration, tenant/branch integration isolation, token lifecycle, offline grant/time behavior, TLS/WSS, route guard completeness, and epoch recovery were not integration-tested. No PostgreSQL service was used.

## Results

| Suite | Command / invocation | Result |
|---|---|---:|
| Backend | `backend\.mvnw.cmd -q test` | 111 passed, 0 failures, 0 errors; 28 suites |
| Flutter | Installed Dart SDK running `flutter_tools.snapshot test --reporter compact` from `frontend` | 52 passed |
| Python M28 tools | Python 3.12.10: `-m unittest discover -s tools/m28 -p 'test_*.py' -v` | 6 passed, 0 failures, 0 errors |

The Python suite required an out-of-sandbox run because Windows denied SQLite fixture creation and temporary-directory cleanup under the sandbox paths. The out-of-sandbox run used only the repository's synthetic temporary fixtures and passed. No database in the repository was passed to these tests.

## Security regression coverage added

### Flutter Edge gate — 5 tests

- All ten legacy `/api/db/*` paths return `426 LEGACY_SQL_DISABLED` before invoking the downstream handler.
- Legacy `POST /api/order` returns `426 GUEST_CAPABILITY_REQUIRED`.
- Capabilities response reports required authentication, unavailable domain operations, and disabled automatic synchronization.
- Unrelated public menu route continues to the configured handler.
- `NetworkDatabase.rawQuery` rejects locally with `CLIENT_UPGRADE_REQUIRED` and does not issue an HTTP request.

### Spring Edge-authoritative write guard — 3 tests

- With the mode enabled, operational mutations are rejected with `503 EDGE_REQUIRED` and a request ID.
- With the mode disabled, the filter passes through (prototype feature flag default behavior).
- Reads and selected cloud administration/unmatched paths pass through.

## Failures encountered and corrected

- The first backend full-suite run exposed a test-only assertion mismatch: the servlet response content type includes `charset=UTF-8`. The assertion was corrected to accept the JSON media type prefix; the final full suite passed.
- The first Flutter regression run used relative URIs, which Shelf rejects as invalid request URIs. Tests were changed to absolute local URIs. The gate also normalizes request paths; the targeted security tests and final full suite passed.
- Python tests initially failed inside the sandbox with `sqlite3.OperationalError: unable to open database file` and Windows `PermissionError` during temporary cleanup. Running the identical suite outside the sandbox using the installed Python 3.12.10 interpreter passed all six tests.
- Initial Flutter tool invocations stalled through the batch launcher and the direct tool reported inability to open the SDK lockfile in the sandbox. Running the installed Flutter tool outside the sandbox completed the tests.

## What these tests establish

- Unit-level route gating prevents the identified generic network SQL paths and guest write path from reaching their old handlers.
- The Flutter database adapter no longer sends generic SQL requests.
- The Spring guard blocks its enumerated operational mutation prefixes when its opt-in setting is enabled.
- Existing backend and Flutter tests pass after the prototype changes; Python maintenance-tool tests pass.

## Not tested / remaining security gaps

- The current host enrollment endpoint and RSA proof helper exist, but host-registration persistence, replay at the database boundary, uniqueness race, tenant/branch integration, credential rotation, and revocation have not been tested. The unit proof tests are documented in the continuation section above.
- No Edge JWT or offline grant validator exists; issuer/audience/algorithm/key/device/session/branch/epoch tests are not applicable yet.
- Offline login is denied until signed grants exist; Argon2id migration was not implemented. Existing SHA-256 password rows remain unchanged.
- No TLS server integration or certificate validation/pairing/renewal test was run. The host service remains disabled by default and loopback-only when enabled.
- WSS is disabled, not implemented; no authenticated WebSocket test exists.
- No integration test proves every Spring operational route is classified or blocked. The guard uses enumerated prefixes and defaults off.
- No PostgreSQL migration, host replacement, backup epoch reset, real database, or data reconciliation test was run for Phase 3A.

## Test conclusion

The tested prototype gates and proof helper pass. These results do **not** establish Phase 3A completion or production security readiness. V35 and host-registration persistence remain unvalidated; Phase 3A remains incomplete; Phase 3B readiness is **NOT READY**.
