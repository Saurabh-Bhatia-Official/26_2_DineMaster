# M28 Implementation Roadmap

**Status:** Phase 2 implementation/validation complete. Phase 3A isolated implementation is in progress; formal owner policies remain release gates. Automatic synchronization, Phase 3B and Phase 4 have not started.  
**Date:** 2026-11-24

The roadmap separates security foundation (Phase 3A) from the shared local business write path (Phase 3B). The repository has a Flutter Edge host/Shelf SQLite service and a separate Spring Boot/PostgreSQL backend. Phase 3A must close the current LAN authentication gap; Phase 3B then replaces generic SQL operations and direct branch-terminal cloud writes with domain commands. Automatic sync remains a later phase.

## Phase 1 — Architecture and schema mapping (documented; owner review remains)

**Work:** Review SQLite create/upgrade paths, all Flyway migrations, JPA entities, API mode, all order writers and LAN server routes. Approve edge/cloud ownership, entity authority, bootstrap policy, payment semantics and device security.

**Exit:** Repository architecture and table-by-table sync allowlist/exclusions are documented in the M28 planning documents. Product/security review of the Phase 3A policies remains pending.

## Phase 2 — Identity, schema and bootstrap migration

**Work:** Additive UUID sidecars, version/timestamps where needed, mapping tables, device/epoch records, cloud processed-change and cursor schemas, and a payment ledger prerequisite. Build backup/reconciliation tooling and rehearsal scripts. Preserve all integer IDs and FKs.

**Exit:** Phase 2 implementation and current validation are recorded in `M28_PHASE_2_IMPLEMENTATION_REPORT.md` and `M28_PHASE_2_FINAL_VALIDATION_REPORT.md`. Real Edge-file inventory/reconciliation and deployment PostgreSQL-version confirmation remain rollout prerequisites. Sync remains disabled.

## Phase 3A — Edge security and authentication foundation

**Current work: isolated prototype implementation and validation.** Design documents remain the contract baseline. Proposed security policies are technical assumptions for local development only; the formal approvals remain pending release gates.

**Design work:** Audit `ServerService`, `NetworkDatabase`, `DatabaseHelper`, `SyncService`, `ApiMode`, Shelf routes, direct SQL callers, direct Spring writes, JWT/auth-device/refresh behavior, role/tenant/branch scope, and offline login. Define Edge host identity, one active host per branch, activation/key proof, device/user JWTs, token expiry/rotation/revocation, offline authorization, TLS trust, branch rebind, restore epoch, API contract, and SQL-proxy migration.

**Implemented prototype boundary:** Shelf is disabled by default and requires TLS certificate/key configuration when enabled. Generic network SQL handlers are removed; stale SQL and guest-order routes receive upgrade-required responses. NetworkDatabase refuses to send SQL. The old unauthenticated WebSocket listener is removed. A minimal capabilities route advertises that domain operations and automatic sync are unavailable. Flutter stops treating SharedPreferences/base64 values as bearer credentials. Spring has an opt-in Edge-authoritative guard for branch operational write routes; its default is `false`.

**Implemented continuation:** Additive V35 defines host/activation records and active-host uniqueness. A Spring control-plane slice issues hashed single-use activations to authorized branch users and consumes them with RSA proof of possession. The feature flag defaults off; enrollment issues no access credential. The backend suite passes 113 tests including proof-helper unit tests. V35 has not run against PostgreSQL.

**Remaining Phase 3A implementation:** Host revocation/rebind and credentials; Edge-specific RS256 grants and validation; refresh rotation; offline authorization, trusted time and Argon2id; authenticated Edge middleware; client certificate trust enrollment and authenticated WSS; path-by-path route classification and integration enforcement; authorized recovery epoch reset; isolated PostgreSQL migration/concurrency tests; end-to-end security tests. Owner approvals remain production release gates. Do not rewrite V34 or weaken existing Spring auth. Do not enable automatic sync.

**Exit:** The Edge identity, credentials, offline grants, TLS/WSS, auth middleware, epoch recovery and route enforcement are implemented and tested; old SQL paths cannot be reached; direct cloud operational writes are rejected when Edge-authoritative mode is on; compatibility is staged. Formal Security/Product/Operations/Finance and other owner approvals remain production-release gates. The authenticated domain operations themselves are delivered in Phase 3B.

**Current status:** Prototype plus enrollment foundation. Phase 3A is **INCOMPLETE**; Phase 3B technical readiness is **NOT READY**; production release readiness is **NOT READY**. The implementation report lists verified work and gaps.

## Phase 3B — Shared local domain write service

**Work:** Create an Edge domain repository/service layer used by POS, waiter, KDS, order history, billing and payment actions. Route all branch operations to the host. Local writes are authoritative in both online/offline states. Retire direct cloud writes for branch terminals, and avoid raw SQL for client domain operations. Keep cloud-only Web behavior explicit.

**Included domains:** Orders/order items, KOT, order transitions, table occupancy, billing/payment events, reservations, customers, and inventory movements. Each command has an authority/permission check, DTO validation, idempotency rule, transaction boundary, state transition, and audit behavior. The detailed plan is `M28_PHASE_3B_DOMAIN_SERVICE_PLAN.md`.

**Exit:** No branch order/payment/KOT/occupancy/reservation/customer/inventory mutation path bypasses the Edge domain service; all required mutations are transactionally grouped on host SQLite; direct PostgreSQL branch writes are blocked or routed through the authorized Edge path; `NetworkDatabase.transaction` is removed from the client/server contract. Phase 4 outbox work has not yet started.

## Phase 4 — Local outbox and state

**Work:** Add `sync_outbox`, `sync_state`, and `sync_conflicts`; emit immutable change events in the same SQLite transaction as business data; attach change UUIDs; define per-entity event schemas. Keep LAN WebSocket refresh events separate.

**Exit:** Crash/rollback tests show no business mutation without its outbox event and no outbox event without its business mutation. Pending events survive restart.

## Phase 5 — Cloud push API and appliers

**Work:** Add authenticated `POST /api/v1/sync/push`, device/branch binding, envelope/payload validation, maximum batch and payload sizes, unique processed-change receipts, idempotent result replay, branch/tenant authorization, schema version check, and explicit handlers for each approved entity. Apply receipt and data atomically.

**Exit:** Retried push yields the same accepted/duplicate/rejected/conflict result; invalid branch/device/foreign references are rejected; no generic unrestricted table upsert exists.

## Phase 6 — Cloud change journal and pull API

**Work:** Add durable cloud change journal for cloud-owned catalog/config changes and accepted cross-branch projections; implement `GET /api/v1/sync/pull` with opaque cursor, branch/device scope, page size limits and tombstones. Implement initial `POST /api/v1/sync/full` as staged paginated bootstrap with a snapshot boundary.

**Exit:** Applying each page and advancing the Edge cursor is atomic; restarting mid-page is safe; no missed/duplicated cursor changes after retry; another branch's private operational rows are not returned.

## Phase 7 — Retry and conflicts

**Work:** Backoff/retry with jitter and cap, classify transient/permanent failures, dead-letter/manager visibility, entity-specific rules, conflict resolution API and audit. Add epoch rejection for restored backups. Handle partial batch outcomes independently by `changeId`.

**Exit:** Network failures preserve data, permanent validation failures do not retry forever, conflicts retain both versions, stale epochs cannot write, and manager resolution is permission-protected.

## Phase 8 — Automatic triggers and lifecycle

**Work:** Start sync after host initialization, on outbox append, on network recovery after real cloud health check, and at configured intervals. Add one single-flight lock, cancellation/shutdown handling and event logs. Do not treat LAN connectivity as internet availability.

**Exit:** Startup backlog, online recovery and retry trigger without a button; concurrent triggers never create overlapping jobs; host shutdown leaves recoverable state.

## Phase 9 — Flutter Sync Status and conflict review UI

**Work:** Add status model/repository/UI for online/offline, pending/failed counts, last success, oldest pending age, epoch/schema problems, conflict review and resolution permissions. Force sync is admin-only support action; normal sync is automatic.

**Exit:** Status derives from durable sync state and API responses; it does not report “synced” based only on WebSocket connectivity.

## Phase 10 — Integration, staged rollout and verification

**Work:** Unit, migration, repository, API and end-to-end tests. Rehearse on copied production-shaped data, then a pilot branch, then staged rollout with feature flag and monitoring. Keep manual support/backup procedure available.

**Exit:** Scenarios below pass with verified DB contents on both sides and reconciliation totals; no loss/duplication in interruption/retry scenarios; rollback plan exercised.

## Required test matrix

| Scenario | Acceptance evidence |
|---|---|
| Online create | SQLite transaction/outbox commits first; cloud applies once; both views map to same UUID |
| Offline create | Local order, items, KOT and outbox are committed; UI continues without cloud |
| Reconnect | Health check triggers automatic push and pull, no manual Sync action |
| Disconnect mid-push | Accepted changes remain acknowledged; uncertain ones safely retry as duplicates; unaccepted ones remain pending |
| Duplicate request/change | Same `changeId` and payment idempotency key do not create another order/payment |
| Conflict | Entity rule yields merge, rejection/proposal or durable conflict; never generic last-write-wins |
| Cloud-to-edge | Catalog/config page applies transactionally and advances cursor only after commit |
| Restart with pending events | Startup resumes from durable outbox and cursor |
| Data-loss check | Row counts, order/payment/invoice sums and event IDs reconcile before/after |
| Epoch restore | Old backup epoch is rejected; authorized new epoch requires reconciliation |
| Branch/device attacks | Wrong device, tenant, branch, actor or cross-branch UUID is rejected and audited |
| LAN host unavailable | UI reports Edge unavailable; no independent client writes that later collide |

## Rollout gates

1. **Gate A:** docs, schema inventory and branch authority approved.
2. **Gate B:** migrations and baseline reconciliation pass on copies; no write sync enabled.
3. **Gate C:** one pilot Edge receives cloud master data and sends a non-financial append-only event.
4. **Gate D:** orders/KOT enabled; compare cloud projection against Edge without using cloud as operational writer.
5. **Gate E:** payment/invoice sync enabled only after cloud ledger and legal numbering are validated.
6. **Gate F:** staged branch rollout; monitor pending age, dead events, conflicts, cursor lag, rejected changes and reconciliation totals.

## Dependencies and ordering changes

The main document says “outbox first”; in this repository, local business writes are not consistently unified, and there is no safe branch device credential. Therefore identity/bootstrap and shared local write boundaries must precede enabling the outbox. Sync status UI comes after durable status fields exist. Payment sync cannot be declared ready until the PostgreSQL payment ledger exists.

