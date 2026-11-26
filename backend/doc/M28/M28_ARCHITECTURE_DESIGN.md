# M28 Architecture Design

**Status:** Proposed design for review. No source or database files were changed to prepare this document.  
**Repository reviewed:** DineMaster Flutter client, embedded Shelf services, and Spring Boot backend.  
**Date:** 2026-11-24

## 1. Executive decision

Use the **Edge host's SQLite database as the operational source of truth for its branch**, including while online. Flutter terminals (including LAN clients) send every branch operation to that Edge host. Each committed business mutation creates an outbox event in the same SQLite transaction. The Edge host synchronizes those events to the Spring Boot service, which validates and applies them to PostgreSQL. PostgreSQL is the central durable copy and the authority for tenant identity, staff/roles, menu/catalog configuration, and branch configuration. Those cloud-owned changes flow back to subscribed Edge hosts through cursor-based pull.

Online and offline branch work must use the same local write path. Internet availability changes only whether sync can run; it must not choose between local SQLite writes and direct cloud order writes. Flutter Web remains an online cloud client unless a separate browser-to-edge use case is explicitly designed.

## 2. Existing architecture (observed)

- `frontend/lib/main.dart` starts `ServerService` and the existing `SyncService` on non-web platforms.
- A host Flutter process opens `dinemaster_restaurant.db` in SQLite. `DatabaseHelper.database` returns `NetworkDatabase` for configured non-host clients and Flutter Web; the non-web host opens SQLite directly.
- `NetworkDatabase` sends SQL operations to the host's Shelf service on port 8082. The service exposes `/api/db/*`, including raw SQL operations. `SyncService` uses a separate WebSocket on port 8083 to broadcast refresh notifications. These are LAN database access and UI invalidation, not cloud replication.
- `ApiMode.defaultUseBackendApi` is `false`; Web forces the backend API. POS and waiter flows branch between local SQL writes and Spring REST writes. Switching modes is a preference, not automatic connectivity recovery.
- Spring Boot runs on port 8081 and uses PostgreSQL with Flyway for normal profiles. Its test profile uses H2. Existing order REST routes mutate PostgreSQL directly.
- `staff_sync_outbox` publishes staff snapshot events, and `order_events_outbox` publishes backend order events. Neither is the edge-to-cloud M28 outbox. No M28 push/pull/status/conflict/full endpoints or cursor store were found.

The existing boundaries and schema differences are documented in [M28_DATABASE_MAPPING.md](M28_DATABASE_MAPPING.md).

## 3. Target topology and ownership

```text
Flutter host / Edge process
  ├─ Domain write service (new boundary; owns local transactions)
  ├─ SQLite (branch operational store + sync metadata)
  └─ LAN API and WebSocket notifications
        ▲                         │
        │ authenticated domain    │ HTTPS sync push/pull
        │ requests                ▼
Flutter LAN clients ─────── Spring Boot cloud API ───── PostgreSQL
                                   │                        │
                                   └── validates, applies ──┘
                                       cloud-owned changes
```

The diagram's domain write service is a **proposed boundary**, not a claim that one already exists. Today clients can send generic SQL to Shelf. M28 should replace that trust model with authenticated domain operations (or an equivalent tightly scoped Edge service). Do not expose a cloud database connection to terminals, and do not let terminals choose arbitrary SQL or branch IDs for sync.

| Component | Target responsibility | Authority |
|---|---|---|
| Flutter POS/waiter/KDS clients | UI, local request IDs, render offline state; call the Edge host for branch operations | No independent branch data authority while connected to the host |
| Edge host service | Authenticate LAN devices/users, enforce branch scope, run domain transactions, write SQLite and outbox atomically, host LAN notifications, run SyncEngine | Operational branch state and offline decisions |
| Edge SQLite | Orders, order items, payment ledger entries, KOT/KDS operational events, table live status, reservation activity, stock movement deltas, local sync state | Source of truth for the branch's operational changes until cloud accepts them; retained as operational authority thereafter for live branch state |
| Spring Boot cloud API | Authenticate devices/users, validate change envelope and payload, enforce tenant/branch scope, deduplicate, apply changes, expose cursors/status/conflict review | API policy enforcement and cloud-owned business configuration |
| PostgreSQL | Central tenant/branch/staff/catalog/configuration records plus accepted branch events and reporting projections | Central source for identity, roles, products/prices/tax/settings, and the durable cross-branch record |
| LAN client device | Send commands to the Edge host and receive invalidation/events; optionally retain cache only | Never pushes its own SQLite file directly to cloud |

**Important authority detail:** accepted branch events are copied to PostgreSQL, but conflict policy must not permit cloud catalog edits to overwrite newer local branch operations. Financial/audit events are append-only; corrections are compensating events, not destructive rewrites.

## 4. Connectivity and write flow

1. Client submits a domain command to the Edge host with an operation idempotency key. The host derives the authenticated user, device, tenant, and branch from credentials/activation state.
2. One SQLite transaction changes business rows and writes one or more outbox events. An event has a UUID `changeId`, stable entity UUID, entity type, operation, base version where applicable, payload, branch UUID, device UUID, and creation time.
3. Commit makes the local order visible immediately. LAN clients receive a post-commit WebSocket invalidation/event. A WebSocket message is never treated as durable data replication.
4. SyncEngine is notified after an outbox commit, after host startup, after internet recovery, and by a periodic timer. A single-flight lock prevents overlapping pushes/pulls.
5. Push is retried with backoff. The cloud records `changeId` in the same PostgreSQL transaction as its apply result. A duplicate returns the stored result.
6. The Edge marks accepted/duplicate events acknowledged. Conflicts and permanent rejections are retained with payload and reason for review; retryable transport errors stay pending.
7. Pull requests changes after the saved opaque cursor and applies a page to SQLite in a transaction. The cursor advances only in the same local transaction that commits the page.

The Edge must continue serving operations when internet is unavailable, provided the host and LAN remain available. If the Edge host itself is unavailable, the current architecture has no safe multi-writer fallback; client-side independent order creation is not supported by this design.

## 5. Authority and conflict rules (proposed)

| Data | Authority and merge rule |
|---|---|
| Orders, order items, order lifecycle, KOT/KDS, payments, invoices, audit/status events | Branch Edge emits immutable ordered events. Cloud validates branch ownership and state transitions; it does not replace events with an older snapshot. Corrections are explicit cancel/void/refund/reversal events. |
| Catalog, categories, prices, tax slabs, staff, roles, security settings | Cloud wins. Edge changes are rejected or recorded as proposals if offline editing is later permitted. |
| Table status/live occupancy | Edge wins; static layout/floor/table configuration is cloud-owned. |
| Inventory | Sync signed stock movements with stable event IDs. Do not sync a mutable stock balance as last-write-wins. Cloud computes central projections. |
| Reservations | Use version check and overlap validation. If competing reservations cannot be safely resolved by the domain service, retain a conflict for manager review. |
| Customers | Define field-level policy before enabling writes from more than one authority. Loyalty points must be append-only ledger events. |
| Settings, user/role/device activation | Cloud-owned and branch-scoped on pull. Never accept these mutations from general business-data push. |

These rules follow Chapter 8.5 of the main technical document, with the table above clarifying the authority boundary for this repository. They are proposals and require product/security review before implementation.

## 6. Proposed M28 service responsibilities

- **Edge `OutboxRepository`:** insert events transactionally, page pending events in sequence, persist attempts/backoff/errors, acknowledge accepted changes, retain dead/conflict records.
- **Edge `SyncEngine`:** startup, connectivity, outbox, and periodic triggers; serialize work; push then pull; health-check the cloud; log outcomes; expose status.
- **Edge `ConflictResolver`:** apply only entity-specific safe policies; persist unresolved conflicts and resolution audit. Do not silently use generic last-write-wins.
- **Cloud `SyncController`:** implement the documented M28 routes; validate size/schema/authentication; return stable per-change results; protect status and conflict actions.
- **Cloud `ChangeApplier`:** explicit allowlisted handler per entity/event. Validate payloads with the same domain rules used by cloud writes and write both entity changes and processed-change receipt atomically.
- **Cloud `CursorService`:** issue opaque, branch/device-scoped cursors with stable paging; never use client wall-clock timestamps as the sole cursor.
- **Device/branch scope:** device activation binds a device UUID to one tenant and branch. Every push/pull checks the authenticated device binding and the branch on every event. Branch IDs in payloads are assertions, not authority.

Existing protected business routes rely on JWT and controller-level authority checks. Device binding and an offline-capable edge credential are not yet a complete M28 implementation and must be added without making sync routes public.

## 7. Sync metadata (proposed; not present as M28 schema today)

The documented names `sync_outbox`, `sync_state`, `sync_conflicts`, `ops.sync_cursors`, and `processed_changes` are proposed M28 structures. Current `staff_sync_outbox`, `order_events_outbox`, and `processed_events` have different purposes and must not be silently reused as if they were equivalent.

At minimum, the local outbox needs immutable change UUID, stable entity UUID, entity type, operation, payload, branch/device IDs, base version, sequence, attempts, next retry, last error, status, created time, and acknowledged time. Local state needs pull cursor per stream, sync epoch, schema version, device ID, and last successful sync. Cloud needs a unique processed-change ID with result, per-device/branch/entity cursor state, and append-only sync audit. Conflict storage must preserve both versions, rule, reason, and resolution actor/time.

## 8. Initial synchronization and epoch protection

1. Back up both databases and produce counts/checksums by branch and entity.
2. Activate the Edge device for a specific branch and assign a server-controlled epoch.
3. Pull cloud-owned bootstrap data (tenant/branch, staff permissions, menu, tax/settings, layout) into a staging area; validate references; atomically promote it.
4. Reconcile pre-existing SQLite and PostgreSQL rows through stable UUID mappings. Never infer identity solely from equal integer IDs. Ambiguous matches are reviewed; preserve both rows until a decision is signed off.
5. Start operational event push from a recorded baseline sequence. Cloud confirms the baseline/epoch before accepting changes.
6. On restore from backup, Edge must present its prior epoch. Cloud rejects stale epochs; an authorized restore flow obtains a new epoch and requires reconciliation of pending changes before syncing resumes.

## 9. Architectural constraints / decisions required before build

- Decide if Flutter Web remains online-only (recommended) and which API it calls.
- Specify LAN authentication and host authorization for the current raw SQL Shelf endpoints before exposing them beyond a trusted development network.
- Define payment ledger semantics. Current cloud stores payment status/method on `orders`, and has no `payments` table; SQLite has a `payments` ledger. M28 must add a cloud payment ledger and idempotent payment event before claiming duplicate-payment safety.
- Define reconciliation for existing cloud and edge rows. Current IDs alone cannot establish identity across databases.
- Define exact event handlers and payload contracts for M28 entities. A generic row dump is not a safe domain sync contract.
- Resolve current profile naming/configuration and deployment topology: local Spring Boot currently targets PostgreSQL; the Flutter Edge host currently owns SQLite. Running the same Spring service on Edge with SQLite is not supported by its current JPA/Flyway schema.

## 10. Existing vs proposed summary

| Capability | Existing | Proposed M28 |
|---|---|---|
| Local branch SQLite | Yes | Remains operational store on Edge |
| LAN client database access | Shelf SQL proxy | Replace/encapsulate with authenticated domain writes |
| LAN live refresh | WebSocket broadcasts | Retain for UI invalidation, independent of database sync |
| Direct cloud business API | Yes; selected by API mode | Use for cloud-only/Web clients; branch terminals write locally first |
| Staff/order backend outboxes | Yes, specialized | Keep distinct from edge change outbox |
| SQLite/PostgreSQL replication | No | Push/pull engine and per-entity appliers |
| Sync cursors, sync conflicts, epochs | No | Add with branch/device scope |

