# M28 Raw SQL and LAN Client Migration Plan

**Status:** Prototype security boundary applied partially. Shelf generic SQL handlers were removed; the Edge middleware returns `426 LEGACY_SQL_DISABLED`; `NetworkDatabase` now fails locally with `CLIENT_UPGRADE_REQUIRED`; the public guest-order POST is gated. No domain-service replacements or client call-site migration are implemented.  
**Scope:** Replace the unauthenticated Shelf SQL proxy without losing supported POS, waiter, KDS, table, reservation, catalog, inventory, customer, and guest-menu workflows.

## 1. Current surface and impact

The Flutter `NetworkDatabase` sends every operation to Shelf on port 8082. The API includes generic query/insert/update/delete and raw SQL methods; `/api/db/batch` can include arbitrary operation kinds. Every non-host client using `DatabaseHelper.database` reaches these routes, and Web can reach them too. The Shelf handlers ignore the bearer header supplied by `sendNetworkRequest`.

The route list is:

`/api/db/rawQuery`, `/api/db/rawInsert`, `/api/db/rawUpdate`, `/api/db/rawDelete`, `/api/db/execute`, `/api/db/query`, `/api/db/insert`, `/api/db/update`, `/api/db/delete`, and `/api/db/batch`.

Callers are not centralized. The most material direct SQL/write callers are:

| Workflow | Existing caller groups |
|---|---|
| POS create/update/pay/hold/delete | `screens/pos_screen.dart`, `order_api_repository.dart` |
| Waiter order create/update/status/water/KOT | `screens/waiter_order_screen.dart` |
| Order history/status/void/delete/bill | `screens/orders_history_screen.dart` |
| KDS status and kitchen reads | `screens/kitchen_screen.dart`, `live_tracking_screen.dart` |
| Tables, occupancy, layout, reservations | `screens/tables_screen.dart`, `admin/components/table_configuration_dialog.dart`, `table_repository.dart` |
| Customers | `screens/customers_screen.dart` |
| Products, recipe/BOM, categories | `screens/products_management_screen.dart`, `recipe_management_screen.dart`, `product_repository.dart` |
| Inventory and reporting | `screens/inventory_screen.dart`, `analytics_screen.dart`, `overview_screen.dart` |
| Login/bootstrap and user settings | `screens/login_screen.dart`, `user_management_screen.dart`, `restaurant_repository.dart`, `user_repository.dart` |

Raw reads are in additional areas: table/product repositories, POS, waiter, order history, KDS, tables, analytics, overview and recipes. The implementation phase must generate an exact method/call-site inventory again and classify each call before route retirement.

The native app can also write directly to Spring REST depending on `ApiMode`. `OrderApiRepository`, POS/waiter, table/customer/product/inventory/reservation/user/branch flows call cloud endpoints. Web forces backend mode in some helpers, while `DatabaseHelper` still returns `NetworkDatabase` for generic DB operations. This mixed routing must be resolved as part of the cutover; otherwise a protected SQL proxy could coexist with direct PostgreSQL mutations that bypass Edge ownership.

## 2. Target replacement

### Client-side boundary

Replace the `sqflite.Database`-over-HTTP contract with a typed `EdgeApiClient` and typed repositories. The client sends a business command with an idempotency key; it never sends a table name, SQL, predicate, column name, generic batch, or arbitrary transaction callback. Read requests are explicit and bounded. A response carries existing integer IDs for Flutter compatibility and stable UUIDs for cross-database identity.

`DatabaseHelper` remains available only to the Edge host process and local-only bootstrap/migration code. A LAN client cannot obtain the host's SQLite connection through that abstraction. Web does not use `DatabaseHelper` to contact Shelf; it is an online-only client with a separately approved operational write policy.

### Host-side boundary

The Edge Shelf service accepts only versioned domain routes behind a single auth middleware. Each command resolves the authenticated principal and fixed host branch before calling a host-side service. Services own SQLite transaction boundaries. Responses do not expose arbitrary query results. WSS notifications contain only branch-scoped invalidation IDs after commit; they are not write receipts or data replication.

Spring's direct REST APIs remain for cloud-owned administration/configuration where allowed. Branch operational APIs must reject or route native branch-terminal mutations through the Edge owner; `ApiMode` is no longer a security toggle.

## 3. Phased client/server migration

No unauthenticated overlap is permitted. The rollout preserves supported workflows through versioned domain routes, forced client upgrade messaging, and staged branch deployment. A previous client may be temporarily unable to operate once the server security release is applied; the compatibility design must communicate this explicitly rather than leave anonymous SQL enabled.

### Stage 0 — Caller and workflow inventory (development prerequisite)

1. Enumerate every `DatabaseHelper.instance.database` caller and every `.query/.insert/.update/.delete/.execute/.raw*` call, including dynamic table names.
2. Tag call sites as read, write, migration/bootstrap, reporting, guest, or test.
3. Enumerate every direct `BackendApiClient` operational mutation and the server controller/permission it reaches.
4. Record required behavior, atomic row set, permissions, branch scoping, idempotency need, offline behavior, and external integration for each call site.
5. Add contract tests mapping each supported workflow to its target API. Do not use runtime production telemetry as a substitute for reviewing source call sites.

### Stage 1 — API and client release (before server enforcement)

1. Implement the approved Phase 3A host/user authentication boundary and Phase 3B domain operations. The client reads `/api/edge/v1/capabilities` and verifies protocol/minimum version.
2. Ship updated clients that use typed domain repositories for each workflow; no repository falls back to direct SQL after an API error.
3. Keep a route compatibility matrix by app version. An old protocol gets `426 CLIENT_UPGRADE_REQUIRED` with a clear update action.
4. Make Web stop using Shelf through `DatabaseHelper`. Until an approved Edge route exists, Web operational mutations are disabled or routed through a separately approved Edge relay; they never write branch operational rows directly to PostgreSQL.
5. Retain the guest menu workflow only if product approves its separate session capability and it passes domain validation/idempotency.

### Stage 2 — Protected compatibility window

If a temporary compatibility adapter is required to bridge updated screens while repositories are converted, it must be explicitly time-limited and authenticated with the same Edge user/device/host/branch context. It must not expose raw SQL. Acceptable adapter operations are named legacy workflows only; it maps each to approved domain service methods.

If a legacy SQL route must exist briefly for rollback diagnostics, it must reject every request lacking a valid Edge credential and be disabled by default outside a controlled development environment. It is not an offline fallback. The design does **not** authorize keeping an anonymous route to protect older client behavior.

### Stage 3 — Enforce Edge-only branch mutations

1. Deploy the Edge auth release and per-operation API authorization.
2. Block direct Spring operational writes from native branch clients. Cloud administrator use and cloud-owned configuration remain in separately authorized routes.
3. Apply explicit route classification to `/api/v1/orders/**`, payment/billing, KOT, reservations, table occupancy, customer edits, and inventory; no ambiguous route remains open as `anyRequest().permitAll()` plus an unreviewed handler.
4. Upgrade the host and clients as one controlled branch wave. Check host certificate, active binding, user grants, and supported protocol before opening service.
5. If any check fails, stop branch mutation with an actionable error. Do not restore direct cloud writes or anonymous SQL to make the screen work.

### Stage 4 — Disable and retire SQL proxy

1. Verify through source analysis and supported-client inventory that no live client needs `/api/db/*`.
2. First deployment returns `410 LEGACY_SQL_DISABLED` for every SQL route and logs a non-sensitive client version/request ID; it does not execute or partially interpret the request.
3. After the agreed observation period and support sign-off, delete the routes, NetworkDatabase HTTP implementation, `NetworkTransaction`, and generic SQL serialization. Keep host local SQLite access for host-only persistence and migration.
4. Remove port 8082 plaintext exposure. Keep only the HTTPS Edge API. Replace port 8083 with authenticated WSS or remove it.
5. Add CI/source scans preventing new `NetworkDatabase` SQL proxy methods or raw client SQL paths.

## 4. Compatibility and rollback

- **No silent protocol break:** capabilities and minimum client version identify incompatibility. The app explains that the Edge host/client requires an update and preserves unsent form input where safe.
- **No silent auth weakening:** missing token, legacy base64 token, unsupported issuer/audience, expired grant, wrong branch, and wrong epoch fail closed.
- **Feature parity first:** each operation has a mapping from existing UI action to new route, error behavior, and permission. Keep button and screen behavior through typed API changes; do not emulate generic SQL.
- **Host availability:** all operational requests target the registered Edge host. If it is offline, show Edge unavailable; independent client SQLite order creation is out of scope because it creates multi-writer conflicts.
- **Rollback:** roll back application binaries only to a version that understands authenticated Edge APIs. Never roll back to public SQL routes or bypass Edge ownership. For schema changes use additive forward fixes; preserve backups and pending local work.
- **Legacy clients:** make update required before each branch's host switches. Once the security release is active, old clients are rejected rather than granted an anonymous grace period. This avoids silent data corruption and unreviewed access while making the compatibility consequence explicit.

## 5. Production rollout gates

Before a production branch cutover, require owner-approved policies, inventory of host/client versions, verified branch binding and UUID ownership reconciliation, successful copy-of-data rehearsals, TLS trust rollout, signed credential lifecycle, full feature parity on the target clients, field support instructions, staged pilot, audit review, tested backup/restore epoch procedure, and explicit approval to block old clients. No production or Neon database was accessed for this plan.

## 6. Acceptance evidence for retirement

### Prototype evidence as of 2026-11-24

- The Shelf SQL endpoint handlers and generic batch executor have been removed from `server_service_io.dart`.
- Requests under `/api/db/*` are denied by middleware before routing, even if a stale handler is later added behind that middleware.
- The Flutter network database and its HTTP transport stubs no longer submit SQL or attach SharedPreferences bearer values.
- `POST /api/order` is denied until a scoped guest capability is implemented.
- Shelf starts only when `DINEMASTER_EDGE_SECURITY_ENABLED=true` and a TLS certificate and private key are configured. Plain HTTP fallback is not available.
- Port 8083's unauthenticated WebSocket listener is removed. Authenticated WSS remains unimplemented.
- These changes intentionally leave LAN SQL-dependent workflows unavailable pending Phase 3B. They are not a production rollout or feature-parity claim.

- All reviewed native domain write call sites use typed Edge APIs; no `DatabaseHelper` network fallback exists.
- No browser client contacts port 8082 or direct Spring operational mutation routes.
- `rg`/static scans find no production caller of generic network SQL methods.
- Edge API integration tests show branch/user/role/device checks and atomic workflows.
- Legacy route probes with no/invalid token return denial and do not change SQLite data.
- Upgraded clients retain order, KOT, payment, reservation, customer and inventory behavior; old clients receive a stable update-required response.
- Port scans/config checks show no plaintext SQL endpoint or unauthenticated WebSocket remains.
