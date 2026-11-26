# M28 Risk Analysis

**Status:** Proposed risk register for review. No code or database files changed.  
**Date:** 2026-11-24

| Risk | Why it applies here | Impact | Mitigation / release gate |
|---|---|---|---|
| Existing SQLite production data differs by age | `DatabaseHelper` uses version 7 but also creates/alters tables lazily in `_ensureTablesExist`; installations may have multiple historical schemas | Missing columns, failed migrations, silent dropped/ignored fields | Inventory actual DB files and `user_version`; test all discovered starting schemas; additive migrations; backup/restore rehearsal. |
| Integer ID collision across SQLite and PostgreSQL | Both use autoincrement integer IDs independently | Wrong order/product/table/user linked without obvious error | UUID sidecar identity and per-system integer map; never equate integer IDs; resolve every FK by UUID. |
| Ambiguous legacy record matching | SQLite and cloud may already contain overlapping data; SQLite orders lack cloud's stable `order_no` format | Duplicate historical orders or accidental merge of different transactions | Dry-run reconciliation reports; only match on validated identifiers; manual review of ambiguous orders/customers; keep both sources intact until approved. |
| Foreign-key translation incomplete | Orders depend on products, tables, users, customers; order items/KOT/recipes add more relationships | Rejected changes, orphan rows or semantically wrong references | Explicit mapping per FK; topological bootstrap; quarantine unresolved events; DB constraints and FK checks at each phase. |
| API compatibility breaks | Java `Long`, Flutter `int`, and routes such as `/orders/{id}` are established | Existing UI/integrations fail after ID type change | Add UUID fields only to sync contracts first; preserve integer PKs/routes; major API migration separately, only after consumer inventory. |
| SQLite/PostgreSQL schemas are not identical | Local products/categories/recipes/payments differ; cloud adds tenant, roles, tax, branch and normalized event tables | Generic row copy can corrupt domain invariants or fail constraints | Explicit allowlisted entity appliers and DTOs; documented transform per table; no generic SQL replication. |
| Payments lack a cloud ledger | SQLite has `payments`; PostgreSQL records status/method on `orders`, not payment transactions | Duplicate payment, inability to audit split/partial/refund entries | Add immutable cloud payment ledger with event UUID and idempotency key before payment sync; use compensating reversal events. |
| Multiple LAN clients race on one Edge host | Clients call network SQL proxy; its transaction implementation does not provide a real host transaction across the callback | Partial order, duplicate open-table orders, KOT/item mismatch | Move domain writes behind host transaction endpoints; serialize/lock table/order operations; host-owned ID and order number allocation. |
| Raw SQL proxy security boundary | Shelf exposes raw query/insert/update/delete endpoints; this is not a branch-scoped domain API | Unauthorized SQL/data disclosure or tenant/branch bypass | Restrict to trusted LAN during transition; authenticate devices/users; remove raw SQL from client-facing production routes; allowlist domain operations and audit. |
| Direct online writes bypass Edge | POS/waiter use REST path when API mode enabled; no local outbox for those API writes | Online/offline data divergence and missing outbox events | Force branch terminal order/payment writes through Edge in all connectivity states; retain separate cloud-only Web flow. |
| Network interruption after cloud commit | Client may time out without knowing whether PostgreSQL committed | Retry creates duplicate order/payment unless idempotent | Cloud `processed_changes` receipt committed atomically with apply; return prior result for duplicate UUID; use stable client-generated keys. |
| Partial batch failure | Batch can contain valid and invalid independent changes | Marking whole batch success loses rejected changes; whole retry can duplicate successful ones | Per-change outcome; ack independently; cloud idempotency; local status updated transactionally per result. |
| Cursor gap or premature cursor advancement | A page can be interrupted during local apply | Permanently missing cloud changes or repeated changes | Opaque stable cursor; apply page and advance cursor in the same SQLite transaction; repeat-safe apply keyed by change UUID. |
| Generic last-write-wins loses business meaning | Orders, payment, stock, KOT and legal documents have different semantics | Financial or operational data overwritten silently | Entity-specific policies; append-only events; stock deltas; version checks for mutable masters; unresolved cases go to review. |
| Edge host outage | Current model centralizes SQLite on one host for LAN clients | Entire branch may stop serving; local client copies cannot safely merge | Document host availability/backup/restore; UPS/automatic restart; do not promise client offline writes; future HA needs explicit consensus/ownership design. |
| Restoring old backup replays stale changes | SQLite restore can roll back acknowledged state/cursor | Stale data overwrites newer cloud state or repeats payment | Cloud-controlled sync epoch; backup restore increments through authorized flow; reject older epoch; reconcile local pending events first. |
| Clock skew and timestamp cursors | Devices may have incorrect clocks; timestamps can tie or move backwards | Missed/reordered changes | Server-issued opaque sequence/cursor, not client `updated_at`; server timestamps for audit; timestamps only diagnostics/conflict policy input. |
| Branch/tenant leakage | Existing API enforces branch scope on many routes; sync is a new bulk endpoint | One device can read/write another branch's records | Device activation bound to tenant/branch; derive scope from token/device; check every event/reference; test cross-tenant and cross-branch rejection. |
| Cloud master data absent at Edge | Product/table/user integer IDs currently assumed locally | Offline order references unknown/deactivated product or user | Full bootstrap and catalog version checks; retain signed local snapshot; business rules specify offline restrictions; don't accept unresolved UUID references. |
| Stock double deduction | Backend recipe deduction jobs and local SQLite stock updates both exist | Same order deducted twice after sync | Assign one stock movement authority per branch; include stable source event and idempotency key; cloud projection consumes event without repeating Edge deduction. |
| Invoice/order numbering collision | Cloud has order/invoice counters; Edge's local order/KOT numbering differs | Duplicate or invalid legal numbers | Edge-controlled branch counter for offline operations; cloud validates uniqueness; preserve issued invoices; include device code only if multi-host is later supported. |
| Schema version mismatch | Edge installations can upgrade at different times | Cloud cannot interpret payload or can return fields Edge cannot apply | Include schema version; negotiated compatibility window; block unsupported writes safely while keeping local data; migrate before replay. |
| Large first sync/backlog | Existing DBs may contain substantial catalog/order history | Memory pressure, long outage, request timeout | Bounded pages, streaming/chunked payloads, backpressure, resumable cursor, initial snapshot token, load tests at target backlog. |
| Security credential misuse | Current LAN SQL proxy and WebSocket are separate from Spring JWT auth | Device impersonation or unauthorized operations | Per-device credentials/rotation/revocation; short-lived user authorization; TLS for cloud; LAN auth and least privilege; audit event actor/device. |
| Rollback after sync accepted data | Cloud may contain new events that older Edge code cannot understand | Rollback app corrupts local state or replays unknown changes | Backward-compatible event versions; feature flags; no destructive down migrations; keep accepted outbox receipts and use a tested forward fix where possible. |

## Highest-priority go/no-go conditions

Do not enable order/payment sync in production until all are true:

1. Every branch mutation uses one host-side transaction path, including LAN clients.
2. UUID identity maps are complete for every referenced row in the pilot branch.
3. Payment ledger and idempotency behavior exist in PostgreSQL.
4. Sync device credentials bind to one tenant and branch, and are tested against cross-branch attacks.
5. Push receipt/apply and local cursor/apply are atomic and idempotent.
6. The pilot's order, item, payment, invoice and stock totals reconcile against signed-off baseline reports.
7. Backup restore and sync-epoch recovery have been rehearsed.

