# M28 Edge API Contract (Proposed)

**Status:** Contract remains a design target. Implemented evidence: fail-closed capabilities/legacy SQL gate and a disabled-by-default Spring host-enrollment foundation. Enrollment returns identity only and issues no credentials. Authenticated sessions, Edge grants, and domain routes are not implemented. Formal owner policies remain release gates.  
**Server:** Flutter/Dart Shelf Edge host, backed by branch SQLite.  
**Transport:** HTTPS is required when the experimental Shelf service is explicitly enabled and configured with a certificate/key. The service is disabled by default. The legacy unauthenticated WSS listener has been removed; authenticated WSS is not yet available. Automatic synchronization remains disabled.

## 1. Contract rules

- The Edge host binds to exactly one registered tenant and branch. Clients do not select or override that binding.
- Every route is explicit and domain-based. No request accepts arbitrary SQL, table names, columns, predicates, or a batch of database operations.
- Staff requests carry an Edge user grant in `Authorization: Bearer <token>`. The token is asymmetric-signed, Edge-audience scoped, expires, and binds user, client device/session, host, tenant, branch, permission version, and epoch.
- Mutations carry `Idempotency-Key: <UUID>` (or a body `requestId` UUID when an idempotency header is not practical). Reuse with an identical canonical request returns the original result; reuse with different content returns `409 IDEMPOTENCY_KEY_REUSED`.
- The server derives user, tenant, branch, and device from validated credentials and host state. Client-provided IDs for these fields are rejected. Resource IDs may be local integer IDs for existing Flutter models or stable UUIDs; the Edge resolves them locally and verifies their branch ownership.
- JSON is schema-validated, bounded in size and nesting, rejects unknown fields for security-sensitive commands, and uses decimal money values with explicit currency. Never trust a client-supplied product price, tax rate, total, payment status, role, or permission.
- Each mutation has a single host-side SQLite transaction that includes all required business rows, lifecycle records, idempotency receipt, and audit entry. Phase 4 will add the sync outbox to this same transaction; this design does not implement it.
- Requests use UTC RFC 3339 timestamps for informational/audit values. Server time controls expiry, sequence, status-transition time, and issued invoice/order numbers.

## 2. Authentication and host control-plane proposals

Cloud control-plane routes run through Spring Boot and use the existing user authentication plus dedicated authorities. These routes manage the Edge identity; they do not let a LAN client alter the host's binding.

| Method/path | Authority and purpose | Result |
|---|---|---|
| `POST /api/v1/auth/edge-host-activations` | Authenticated branch operator with `edge_host:activate`; create one-use activation. | **Development implementation:** disabled by default; hashed code, 15-minute expiry, tenant and verified branch assignment checks. |
| `POST /api/v1/auth/edge-host-activations/consume` | Public only for this operation; activation code, host RSA public key, nonce and proof signature. | **Development implementation:** disabled by default; validates >=2048-bit RSA proof over activation UUID, nonce and key fingerprint; returns host UUID/tenant/branch/epoch and `credentialIssued:false`. V35 uniqueness is not yet PostgreSQL-tested. |
| `POST /api/v1/auth/edge-hosts/{hostId}/credentials/renew` | Host proof-of-possession using active registered key; requires active state and matching epoch. | Rotates the opaque refresh credential and issues short-lived sync access. |
| `POST /api/v1/auth/edge-hosts/{hostId}/revoke` | Tenant/branch administrator with proposed `edge_host:revoke`; reason required. | Revokes registration and refresh families, advances fencing generation, emits audit. |
| `POST /api/v1/branches/{branchId}/edge-host/rebind` | Online, high-privilege operator action with explicit old-host confirmation and reason. | Revokes old host, increments epoch/generation, opens a new activation. Rebind is not available offline. |
| `POST /api/v1/auth/edge-user-grants` | Authenticated user plus selected branch and registered active Edge host. | Issues a signed Edge API grant only if tenant membership, branch assignment, permissions, host binding, and host epoch are valid. Proposed expiry is one shift; approval required. |
| `POST /api/v1/auth/edge-hosts/{hostId}/restore-epochs` | Separate `edge_host:restore` permission with step-up authentication and audit reason. | One-use signed reset authorization bound to host, tenant, branch, prior epoch, next epoch, and backup ID. |

The existing `/api/v1/auth/devices/activation-codes` and `/devices/activate` paths create generic user devices, not an Edge host credential. They may be reused internally only after Edge-host type, key possession, uniqueness, epoch, and revocation requirements are designed. Do not silently change their existing response contract.

### Token claims and validation

The proposed Edge user token is asymmetric-signed and has `iss`, `aud=dinemaster-edge-api`, `typ`, `kid`, `sub` (user identity), `jti`, `iat`, `nbf`, and `exp`. Its scope claims bind `edgeHostId`, `clientDeviceId`, `tenantId`, `branchId` (prefer canonical UUID plus current local mapping), `syncEpoch`, `permissionVersion`, and explicit permission scopes. Shelf validates all claims, algorithm allowlist, signature, trusted key ID, time bounds, token purpose, host binding, current epoch, and local revocation state. Never accept an arbitrary JWT algorithm or an unsigned token.

Edge host sync tokens use a different `aud=dinemaster-sync` and a device identity subject, one tenant/branch/epoch, host key thumbprint, and narrow sync scopes. A user token cannot call sync-control routes, and a sync token cannot call staff domain routes.

For offline authorization, the client must already hold an unexpired cloud-signed Edge grant. It cannot obtain a new grant or refresh token while cloud is unavailable. Offline grant duration, allowed operations after expiry, last-trusted-time behavior, and permission revocation delay require Security/Product approval.

## 3. Edge discovery and session routes

| Method/path | Authentication | Purpose |
|---|---|---|
| `GET /api/edge/v1/capabilities` | Public, minimal response | **Prototype implemented:** returns protocol/minimum client version, `authenticationRequired`, `domainOperationsAvailable:false`, and `automaticSynchronizationEnabled:false`. A host installation ID is not yet provided. Does not return branch/customer data. |
| `POST /api/edge/v1/sessions` | Valid Edge grant | Establish a server-side local session record bound to `jti`, host, branch, user and client device. Idempotent for the same grant/session nonce. |
| `GET /api/edge/v1/session` | Valid Edge grant and active session | Return current principal, fixed branch, permitted operations, expiry and protocol version. |
| `DELETE /api/edge/v1/session` | Valid grant | Revoke local session immediately; cloud revocation follows its own protocol. |
| `GET /api/edge/v1/health` | Public minimal liveness; no data | Distinguish service availability from authenticated readiness. Do not expose paths, database names, credentials, or branch records. |

Every protected request validates user, device/session, host, tenant, branch, token expiry, epoch, role/permission, and payload. Authentication and authorization failures do not reveal whether another tenant's user or resource exists.

## 4. Domain API proposals for the Phase 3B migration

The session, health, host lifecycle, token, recovery, and domain routes remain design targets. A route is not implemented just because a current Spring route has the same noun. Shelf denies `/api/db/*` with `426 LEGACY_SQL_DISABLED` before route dispatch and denies `POST /api/order` with `426 GUEST_CAPABILITY_REQUIRED`; the generic SQL handlers have been removed. The Flutter `NetworkDatabase` refuses to issue generic SQL requests and raises `CLIENT_UPGRADE_REQUIRED` locally. Spring reports 151 MVC mappings at startup; the complete path-by-path operational classification and guard integration coverage are unfinished, so Edge-authoritative mode is not rollout-safe.

### Orders and order items

| Method/path | Permission | Atomic behavior |
|---|---|---|
| `POST /api/edge/v1/orders` | `order:create` | Validate allowed order type, table/customer/product references and item quantity; derive branch and authoritative price/tax from Edge catalog; allocate a local order ID and order number; write order, items, initial status event, KOT rows where required, occupancy update and idempotency receipt in one transaction. |
| `GET /api/edge/v1/orders/{orderId}` | `order:view` | Return branch-scoped order, item and lifecycle projection. |
| `POST /api/edge/v1/orders/{orderId}/items` | `order:update` | Append/update items only in legal state; reprice from Edge catalog and version-check. |
| `POST /api/edge/v1/orders/{orderId}/confirm` | `order:create` | Verify pending state, immutable request key, table/session and item availability; transition once. |
| `POST /api/edge/v1/orders/{orderId}/status-transitions` | State-specific permission (`order:cancel`, `order:void`, `order:park`, etc.) | Validate legal transition, reason and optional approver; append history and update projection atomically. |
| `POST /api/edge/v1/orders/{orderId}/bill` | `bill:generate` | Snapshot line prices, discounts, tax, currency, and totals; assign a branch-scoped invoice number exactly once. Reprints are read-only. |

Illustrative create request:

```json
{
  "requestId": "UUID",
  "tableId": 42,
  "customerId": 12,
  "orderType": "DINE_IN",
  "items": [{ "productId": 8, "quantity": 2, "notes": "less spicy" }],
  "notes": ""
}
```

It deliberately contains no `tenantId`, `branchId`, role, `price`, `tax`, or `totalAmount`. Response includes existing local integer IDs for compatibility plus stable UUIDs, server-calculated totals, state/version, and `createdAt`.

### KOT/KDS and order lifecycle

| Method/path | Permission | Atomic behavior |
|---|---|---|
| `GET /api/edge/v1/kots?orderId=...` | `kot:view` | Branch-filtered KOT projection. |
| `POST /api/edge/v1/orders/{orderId}/kots` | `kot:create` (or order-confirm workflow) | Idempotently create required KOT rounds; order confirmation may invoke this service within its transaction. |
| `POST /api/edge/v1/kots/{kotId}/items/{itemId}/status` | `kot:update_status` | Enforce allowed kitchen transitions, expected version, chef/station assignment policy and status audit. |
| `POST /api/edge/v1/kots/{kotId}/assignments` | `kot:update_status` | Validate active staff belongs to the branch; record assignment and audit. |

An order transition must not leave order item/KOT state inconsistent. Either one service transaction changes both, or a deliberate state transition creates an internal durable follow-up task with a visible pending state; a WebSocket notification alone is not a follow-up record.

### Table occupancy

| Method/path | Permission | Behavior |
|---|---|---|
| `GET /api/edge/v1/tables` | `table:view` | Branch-scoped table/layout and derived occupancy. |
| `POST /api/edge/v1/tables/{tableId}/occupancy` | `table:update_status` | Only explicit operational transitions such as seat/open or close/clear; lock table/open-order state and check occupancy in the order transaction. |
| `PUT /api/edge/v1/tables/{tableId}/layout` | `table:manage_layout` | Separate configuration mutation; not allowed through a waiter/guest session. |

Occupancy should be derived from open orders/reservation sessions where possible. The client must not set arbitrary `status='Occupied'` or clear an occupied table while an open order remains.

### Billing and payments

| Method/path | Permission | Behavior |
|---|---|---|
| `POST /api/edge/v1/orders/{orderId}/payment-events` | `payment:record` | Append one immutable payment event with idempotency key; explicit branch currency; verify amount, order state, actor/device scope. Never trust/overwrite `orders.payment_status` as the ledger. |
| `POST /api/edge/v1/payment-events/{paymentId}/reversals` | `payment:reverse` plus step-up/manager rule if approved | Append a reversal linked to original payment, enforce same order/currency and remaining refundable amount, idempotently reject key reuse with changed payload. No update/delete of prior events. |
| `GET /api/edge/v1/orders/{orderId}/billing` | `order:view` or approved billing permission | Return branch currency, bill snapshot, and payment event projection. |

Currency is branch-configured. The proposed Indian branch default is INR only after geography is verified and explicitly stored. The API rejects missing/unknown currency; it never infers currency from locale or historical order status.

### Reservations

| Method/path | Permission | Behavior |
|---|---|---|
| `POST /api/edge/v1/reservations` | `reservation:create` | Validate branch-local table, time window and guest count; check overlap under a transaction/lock; use request idempotency. |
| `GET /api/edge/v1/reservations` | `reservation:view` | Bounded, branch-scoped date/page query. |
| `POST /api/edge/v1/reservations/{id}/transitions` | `reservation:update` | Legal status transitions only; keep reservation and table occupancy consistent. |

### Customers

| Method/path | Permission | Behavior |
|---|---|---|
| `POST /api/edge/v1/customers` | `customer:manage` | Validate/normalize allowed fields, privacy/consent rules, and branch/tenant dedupe policy; idempotency avoids duplicates. |
| `PATCH /api/edge/v1/customers/{id}` | `customer:manage` | Version check and field allowlist; never accept loyalty balance as a caller-set scalar. |
| `POST /api/edge/v1/customers/{id}/loyalty-events` | Approved loyalty permission | Append an idempotent earn/redeem/correction event; derive balance. |

### Inventory

| Method/path | Permission | Behavior |
|---|---|---|
| `GET /api/edge/v1/inventory` | `inventory:view` | Branch-scoped stock projection. |
| `POST /api/edge/v1/inventory-movements` | `inventory:receive`, `inventory:adjust`, or consumption permission | Append an idempotent, signed stock movement; validate reason, item, units and actor; update projection in the same transaction. |
| `GET /api/edge/v1/inventory-movements` | `inventory:view` plus allowed history scope | Paginate immutable branch movements. |

Do not expose `PUT current_stock` as the primary operation. Stock level is a projection from opening balance and append-only movements; corrections are explicit adjustment events.

## 5. Response and error contract

Successful reads return `200`; creates return `201`; accepted commands with no representation return `204`. All responses include `X-Request-Id`.

```json
{
  "error": {
    "code": "BRANCH_SCOPE_DENIED",
    "message": "This operation is not available for the selected branch.",
    "requestId": "UUID",
    "retryable": false
  }
}
```

| Status/code | Meaning | Client action |
|---|---|---|
| `401 AUTH_REQUIRED`, `TOKEN_EXPIRED`, `TOKEN_INVALID` | Missing/invalid/expired grant | Reauthenticate/renew only when cloud is reachable; never retry raw SQL. |
| `403 PERMISSION_DENIED`, `BRANCH_SCOPE_DENIED`, `DEVICE_REVOKED`, `EPOCH_STALE` | Authenticated but unauthorized/stale | Stop request, explain action, audit; do not fall back to cloud mutation. |
| `409 VERSION_CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, `ILLEGAL_TRANSITION`, `TABLE_OCCUPIED` | Business state conflict | Reload state or present manager resolution; a retry uses the same idempotency key only for the same payload. |
| `422 VALIDATION_FAILED`, `CURRENCY_UNKNOWN` | Payload/business validation failed | Correct user input; no retry. |
| `426 CLIENT_UPGRADE_REQUIRED` | Client protocol below supported minimum | Show required update and support instructions; no anonymous compatibility path. |
| `503 EDGE_UNAVAILABLE`, `OFFLINE_GRANT_EXPIRED` | Edge unavailable or authorization lease expired | Preserve unsent UI input locally if approved, show status; never write a branch mutation directly to cloud. |

Return generic not-found for inaccessible cross-branch resources to avoid existence disclosure. Never put tokens, passwords, activation codes, or full payment details in response logs.

## 6. Route classification for transition

| Existing route/path family | Target classification |
|---|---|
| `/api/db/*` | Remove from new clients. Temporarily reject missing/invalid Edge grant during staged rollout; no anonymous fallback. After caller inventory reaches zero, return `410 LEGACY_SQL_DISABLED` and remove handlers. |
| `/api/order` | Replace staff/general write use with domain order commands. Preserve guest digital-menu flow only as an explicit signed QR/table-session endpoint with rate limiting and idempotency. |
| `/api/menu`, `/menu` | Public, read-only and field-limited, if product confirms public menu is intended. |
| `/api/v1/orders/**`, KOT, reservation, table occupancy, payment, inventory operational mutations | Classify and enforce Edge ownership before native branch terminals use Phase 3B. Keep direct Spring routes only for explicitly approved cloud-admin/projection behavior; do not accept direct terminal writes. |
| `/api/v1/catalog`, staff, branch settings | Cloud-owned admin/config operations where permitted; their pull to Edge is a later sync concern. |
| `/ws` / port 8083 | Replace with authenticated branch-scoped WSS notifications; clients treat notification only as invalidation. |

## 7. Contract acceptance checks (for later implementation)

- Wrong user, client device, host, tenant, branch, permission, audience, epoch, or key ID cannot perform an operation.
- Altering tenant/branch fields in a body does not change authorization; unknown scope fields are rejected.
- Expired, not-yet-valid, wrong-audience, wrong-purpose, revoked, replayed, wrong-host, and stale-epoch tokens are denied.
- Identical idempotent retry returns one durable result; changed-payload key reuse conflicts.
- Oversized/deep/malformed request payloads are rejected before a DB transaction.
- Every transaction rolls back all domain effects on failure; domain actions have corresponding audit records.
- Old clients see a documented version error and never reach unprotected SQL.
- Guest QR access cannot query arbitrary records, use staff permissions, or cross tables/branches.
