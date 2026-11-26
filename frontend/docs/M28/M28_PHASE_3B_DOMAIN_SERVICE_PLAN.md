# M28 Phase 3B — Shared Local Domain Write Service Plan

**Status:** Preparation design only. Phase 3B implementation has not started.  
**Dependency:** Phase 3A policy approval, Edge authentication, host registration, TLS, and API boundary must be implemented and security-reviewed first.

## 1. Goal and invariants

Phase 3B replaces Flutter's direct SQL writes, Shelf generic SQL proxy calls, and branch-terminal direct Spring mutations with one authenticated Edge domain command path. The Edge host remains the branch operational authority online and offline. Each domain command must have an explicit request shape, permission, validation rules, idempotency behavior, transaction, and response.

Invariants:

- A LAN client never chooses SQL, table names, branch scope, role, permissions, authoritative price/tax, totals, order number, or current payment state.
- Edge derives tenant/branch from the authenticated host binding and resolves resources inside that branch.
- One command has one complete SQLite transaction; if any required write fails, all writes roll back.
- Retried commands use the same idempotency key. The host stores the canonical request fingerprint and response in the transaction. A changed payload with the same key is rejected.
- Business lifecycle/audit rows are written with the projection change. Notifications are emitted after commit.
- No direct cloud API path can write operational order/payment/KOT/occupancy/reservation/inventory data for a branch terminal.
- Phase 4 later adds the synchronization outbox in the same domain transaction. Phase 3B does not implement sync or outbox emission.

## 2. Service boundary and transaction approach

Create one Edge application service per business aggregate/use case. UI widgets call typed repositories, repositories call authenticated Edge routes, and Shelf controllers validate/authenticate then invoke the service. The service receives a trusted `RequestPrincipal` created by Phase 3A middleware; it does not accept a principal from JSON.

```text
Flutter view -> typed repository -> HTTPS Edge command -> auth middleware
                                                   -> application service
                                                   -> SQLite transaction
                                                      business rows
                                                      status/audit ledger
                                                      idempotency receipt
                                                   -> commit -> notification
```

The host must not implement one client round trip per SQL statement. The Edge receives a domain command and performs all reads/checks/writes on the host in one transaction. `NetworkDatabase.transaction` and `/api/db/batch` are retired; they cannot provide domain invariants across multiple arbitrary calls or safe read-after-write behavior.

Every create/update command follows:

1. Authenticate the Edge grant and derive principal/host/tenant/branch/epoch.
2. Check the operation permission and any step-up/manager requirement.
3. Validate request schema and idempotency key; canonicalize payload and calculate a fingerprint.
4. Begin SQLite transaction; check existing idempotency receipt. Return prior result for same fingerprint; reject changed fingerprint.
5. Resolve local integer IDs from stable UUIDs where needed and verify all resources belong to the bound branch.
6. Lock/serialize the aggregate (SQLite write transaction or explicit version compare), apply domain rules, write projections and append-only history/ledger events, then store the idempotency result and audit event.
7. Commit and publish invalidation. On rollback, publish nothing.

Use schema constraints in addition to service checks. Include bounded timeout/retry behavior for SQLite busy errors; retry the whole idempotent command, not only the final SQL statement.

## 3. Domain migration map

| Domain | Current write paths to inventory | Required authority and permission | Transaction boundary / idempotency | Rules and migration notes |
|---|---|---|---|---|
| Orders and order items | POS, waiter, order history local DB; `/api/v1/orders/**` direct cloud routes; Shelf `/api/order` guest route | Edge user and host; `order:create`, `order:update`, confirm/cancel/void-specific permissions | Create order + items + initial status + required KOT + table occupancy + receipt in one transaction. Request UUID prevents duplicate order creation. Item changes version-check aggregate. | Derive product price/tax from branch catalog. Validate table/customer/product references in branch and state. Do not trust client totals or duplicate order-number creation. Preserve local integer IDs and Flutter models. Guest order is a separate signed QR capability. |
| Order status transitions | POS, waiter, history, KOT actions and direct Spring transition routes | Permission per transition (`order:cancel`, `order:void`, `order:park`, `order:reopen`) and manager approval where required | Status projection + status-history event + idempotency receipt together. Compare expected version/current state. | Implement explicit transition graph; reject illegal transitions; record actor, reason, approver, device and server time. No generic status update endpoint. |
| Kitchen Order Tickets | Waiter/POS generation and kitchen screen updates; Spring `/api/v1/kitchen-tickets/**` | `kot:create`, `kot:view`, `kot:update_status`; chef/station membership | KOT rounds/items and related order-item statuses update together. Request ID prevents duplicate print/round creation. | Validate state graph and station/branch; preserve immutable KOT history; retries do not duplicate tickets. Reprint is an authorized command, not a second ticket. |
| Table occupancy | POS/waiter/tables updates; direct `/api/v1/tables/{id}/status` | `table:view`, `table:update_status`, `table:manage_layout` for static layout only | Opening order claims occupancy with order transaction; closing/canceling recomputes/clears only if no other active order/reservation remains. | Prefer derived occupancy from active orders/session; reject arbitrary `Occupied/Available` writes inconsistent with order state. Table layout is a separate cloud-owned/admin workflow pending product authority decision. |
| Billing and payments | POS/order history local `payments`; direct Spring `/api/v1/orders/{id}/payment` and bill routes | `bill:generate`, `bill:reprint`, `payment:record`, proposed `payment:reverse`; step-up policy for refunds/reversals | Bill snapshot and numbering transaction; payment event insert + idempotency receipt + reversal accumulator in one transaction. Each payment/reversal is a new immutable event. | Currency comes from branch config. No fabrication from `orders.payment_status`; no update/delete of event. Validate original payment, order, amount, currency and reversal sum. Legacy history is only reconciled from verified receipts and finance approval. |
| Reservations | Tables screen local SQL and Spring `/api/v1/reservations/**` | `reservation:view/create/update` | Reservation row + status log + occupancy effect + idempotency receipt; serialize overlapping table/time checks. | Validate branch/table and time range. Reject overlapping active reservations; cancellation is a transition, not hard delete. |
| Customers | Customer screens/repositories local SQL and Spring `/api/v1/customers/**` | `customer:view/manage`; field-level privacy access | Customer write and dedupe/idempotency receipt together; use version/expected version for updates. | Product must decide global-tenant vs branch-local customer ownership and phone reuse. Loyalty balance is derived from immutable events, not a scalar write. Audit PII access and restrict response fields. |
| Inventory operations | Inventory screen/repository local SQL and Spring `/api/v1/inventory/**` | `inventory:view`, `inventory:receive`, `inventory:adjust`, or consumption role | Append movement + update local projection + idempotency receipt in one transaction. | Use immutable receipt/issue/adjustment/waste/consumption movements and stable source IDs. Never retry by decrementing stock twice; reject unbounded or invalid unit conversions. Cloud sync later projects movements, not balances. |

## 4. Authorization matrix

Every route receives an operation-specific authority, resource scope, actor rules, and audit requirements. The permission catalog should reuse current Spring permission codes where semantics match, not roles hard-coded in Flutter.

| Data / operation | Request actor | Required checks |
|---|---|---|
| Create/update order | Waiter, cashier, manager or approved role | User active; token permission; host active; branch fixed; table/product/customer belong to branch/tenant; order state allows operation. |
| Cancel/void/discount | Authorized manager/cashier | Dedicated permission, reason; approval and amount limits as Product/Finance define; actor and approver distinct where required. |
| KOT status | Assigned/authorized kitchen user | Branch, station, assignment, current state and expected version. |
| Table layout | Owner/manager with layout permission | Static branch configuration only; does not confer occupancy/payment authority. |
| Payment/reversal | Cashier/manager with payment permission | Order branch, configured currency, positive precision-limited amount, idempotency; reversal must reference an original event and obey refund policy. |
| Reservation | Host/waiter/manager per permission | Branch/table/time ownership and overlap rule. |
| Customer data | Staff permission and specific field allowlist | Branch/tenant scope, PII minimization, consent/retention policy, audit. |
| Inventory | Stock manager/authorized staff | Branch/item/unit scope, movement type, reason, source idempotency and negative-stock policy. |

All checks run on the Edge server. UI visibility and branch-selection preference are convenience only. Tenant/branch mismatch should return a non-disclosing error. The current backend `BranchScopeService` can inform the policy, but Shelf must enforce it locally from the signed grant and host binding.

## 5. Preventing direct cloud-write bypass

Before a branch client uses Phase 3B:

1. Classify each existing Spring route as Edge-routed operational, cloud-admin/configuration, public guest, read-only, or sync-only.
2. For operational writes, either reject branch-terminal direct mutation or expose an authenticated gateway that forwards the command to the registered Edge host and returns the host result. Do not write the same operation independently to PostgreSQL.
3. Keep cloud persistence as a later synchronized projection; no controller updates a second source of truth.
4. Make API mode an enforced deployment/client capability, not a user preference for business ownership.
5. Ensure Web's direct cloud operational path is disabled unless an approved Edge relay preserves Edge ownership. Web continues cloud-owned administration/reporting online only.
6. Return explicit `EDGE_REQUIRED`/`EDGE_UNAVAILABLE` with request ID. No retry path switches from Edge to direct cloud mutation.

Direct admin actions such as menu/price/staff configuration can remain cloud-owned if policy approves. Their replication into Edge happens in later sync phases; Phase 3B does not implement that replication.

## 6. Delivery order and gates

1. Build a workflow-by-workflow call-site map and acceptance examples.
2. Implement the typed Edge API/client and core order aggregate first; keep all old client call sites identifiable during conversion.
3. Convert waiter/POS order and order-item creation, confirmation, status, KOT, and occupancy together. Do not convert one table write while leaving the related status/KOT write generic.
4. Convert billing/payment only after Finance approves branch currency, reversals/refunds, invoice numbering, and historical reconciliation.
5. Convert reservations and customers after overlap/dedupe/ownership rules are approved.
6. Convert inventory to movement commands after units, negative stock and adjustment authority are approved.
7. Remove direct cloud operational calls and raw network DB calls; then meet the raw SQL retirement gates in `M28_RAW_SQL_MIGRATION_PLAN.md`.
8. Run integration tests with local Edge SQLite and a network client; offline means cloud is unavailable while the Edge host/LAN is available. Host-down has no independent-writer fallback.

## 7. Phase 3B exit criteria

- No branch operational mutation uses generic `Database` SQL over HTTP or directly writes PostgreSQL.
- All command paths derive branch/user/device/permissions from Phase 3A auth and share one host service.
- Create/update/status/payment/reservation/inventory operations have validated idempotency and expected-version behavior.
- Orders, items, KOT, status/audit and occupancy are transactionally consistent under success, validation failure, retry, and process interruption.
- Payment and reversal history is immutable; duplicate request and concurrency behavior is tested against SQLite transaction semantics before cloud-sync design.
- Public guest-order behavior is separate, token-scoped and approved.
- Online and offline branch behavior uses identical Edge commands; no cloud fallback occurs during Edge/network errors.
- No Phase 4 outbox or automatic sync has been implemented as part of Phase 3B.
