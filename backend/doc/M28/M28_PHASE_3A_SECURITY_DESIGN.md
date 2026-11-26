# M28 Phase 3A — Edge Security and Authentication Design

**Status:** Architecture baseline for an isolated prototype. A fail-closed prototype slice is implemented; host identity, Edge grants, complete authentication, and authenticated WSS remain unimplemented. Formal owner approvals remain release gates. See `M28_PHASE_3A_IMPLEMENTATION_REPORT.md` for evidence.  
**Date:** 2026-11-24  
**Scope:** Phase 3A design and isolated prototype; no automatic synchronization, Phase 3B, Phase 4, production access, or production database access.

## 1. Decision summary

Phase 3A establishes the identity and authorization boundary around the Flutter Edge host before Phase 3B moves application writes behind domain services. The current Edge is a Flutter desktop process running a Dart Shelf server over SQLite; it is not the Spring Boot process. The Spring Boot service separately serves PostgreSQL. This distinction must remain explicit: Spring cannot be assumed to serve SQLite operations, and a LAN client must not receive database credentials or SQL execution authority.

The recommended target is:

1. One registered, active Edge host is bound to one tenant and one branch. The cloud controls registration, branch binding, credential issuance, revocation, and epoch changes.
2. The Edge host is the only operational data gateway for its branch. LAN clients identify a user and client device; the host derives tenant and branch scope from its own registered binding and authorization context.
3. LAN APIs use HTTPS and authenticated, versioned domain requests. A signed capability token is scoped to the Edge API audience, one branch, one user, one device/session, permissions, and the current host epoch. No tenant/branch value in request JSON grants access.
4. Cloud-issued asymmetric signatures are used for Edge credentials. Shelf receives only verification keys; it does not receive a shared cloud HMAC secret. Edge-to-cloud host credentials are distinct from user credentials.
5. The current `/api/db/*` interface is first gated so unauthenticated callers fail, then clients are moved to domain APIs, then the SQL routes are disabled and removed. An old client receives a clear upgrade response; it does not get an anonymous fallback.
6. Offline authorization is bounded and explicit. A valid signed offline grant can keep a session working during an outage only until its approved expiry. Revocation cannot reach a disconnected host immediately; that exposure window must be approved.

**No policy in this document is approved.** In particular, the proposed one-host rule, offline session duration, token claims/lifetime, local password verification, TLS trust bootstrap, Web write scope, and branch rebind procedure require the owner approvals in `M28_POLICY_APPROVAL_CHECKLIST.md` before implementation.

## 2. Repository audit: current topology and data paths

### 2.1 Flutter, Shelf, SQLite, and Spring

| Component | Observed behavior | Security consequence |
|---|---|---|
| `ServerService` (`frontend/lib/services/server_service_io.dart`) | Non-web startup hosts Shelf on `0.0.0.0:8082` and a separate `0.0.0.0:8083` WebSocket. Both are plain HTTP/WS in this code. | The host listens on all IPv4 interfaces; LAN access is not restricted to a trusted peer by the bind address. No TLS termination is shown here. |
| `DatabaseHelper.database` | Host opens `dinemaster_restaurant.db`; a non-host returns `NetworkDatabase(serverIp, 8082)`. Web also returns `NetworkDatabase` on port 8082, using the selected server IP or the configured backend URL's host. | A web build is not currently purely cloud REST: direct database abstraction calls can be routed to a Shelf SQL endpoint. |
| `NetworkDatabase` | Encodes `query`, `insert`, `update`, `delete`, raw SQL, and batch calls as HTTP POSTs to `/api/db/*`. | Callers can request table operations and SQL rather than a domain operation. |
| `sendNetworkRequest` | Adds a bearer value from secure storage or legacy SharedPreferences, then sends the POST. | Sending a bearer token is not authentication: the Shelf handlers do not validate it. Local-mode base64 session values may also be attached as bearer strings. |
| `ApiMode` | `kIsWeb` forces Spring API mode; native mode uses a preference. `defaultUseBackendApi` is false, while `login_screen.dart` currently uses true as its missing-preference fallback. `UserRepository` uses `ApiMode`'s false fallback. | Routing can differ by screen/repository and first-run login paths. The preference is a routing choice, not an authorization policy. |
| Spring Boot | Runs the cloud REST API on the configured backend URL (current code comments identify port 8081) and uses PostgreSQL/Flyway. | Existing APIs are a second write path. They must be restricted for branch operational mutations before Edge ownership is enforced. |

`NetworkDatabase.transaction` buffers some operations into `/api/db/batch`; Shelf executes that batch inside a SQLite transaction. It is not equivalent to a SQLite transaction callback with full read-after-write/isolation semantics: the client wrapper queues operations, and the batch supports `continueOnError`, which may retain partial results by design. A domain request must define its own complete transaction on the host.

### 2.2 Shelf route inventory

All of the following routes are declared in `server_service_io.dart`. The route handlers do not perform bearer-token verification, user resolution, role checks, device checks, tenant checks, or branch checks. `NetworkDatabase` is the direct client for the `/api/db/*` routes.

| Shelf route | Current behavior / `NetworkDatabase` caller |
|---|---|
| `POST /api/db/rawQuery` | Executes caller-provided SQL through `rawQuery`; called by `NetworkDatabase.rawQuery` and every screen/repository that uses `db.rawQuery`. |
| `POST /api/db/rawInsert` | Executes caller-provided SQL insert; `NetworkDatabase.rawInsert` and queued batch operations. |
| `POST /api/db/rawUpdate` | Executes caller-provided SQL update; `NetworkDatabase.rawUpdate` and queued batch operations. |
| `POST /api/db/rawDelete` | Executes caller-provided SQL delete; `NetworkDatabase.rawDelete` and queued batch operations. |
| `POST /api/db/execute` | Executes caller-provided SQL; `NetworkDatabase.execute` and queued batch operations. |
| `POST /api/db/query` | Caller chooses table, columns, filters, grouping, having, order, limit, and offset; `NetworkDatabase.query`. |
| `POST /api/db/insert` | Caller chooses a table and values; `NetworkDatabase.insert`. |
| `POST /api/db/update` | Caller chooses a table, values, and predicate; `NetworkDatabase.update`. |
| `POST /api/db/delete` | Caller chooses a table and predicate; `NetworkDatabase.delete`. |
| `POST /api/db/batch` | Executes a list of raw or table operations inside a host transaction; supports caller-selected `continueOnError` and `noResult`; `NetworkBatch.commit/apply`. |
| `POST /api/order` | Public menu-order workflow; derives branch from `DatabaseHelper.currentRestaurantId` (default 1), checks table/products belong to that branch, then inserts order, items, status log, KOT, and table occupancy in one local transaction. It has no user/device authentication or request idempotency. |
| `GET /api/menu`, `GET /menu` | Public product menu data/page. Keep public menu behavior separate from protected operational data. |

The WebSocket on port 8083 accepts upgrades without an authorization check, accepts arbitrary client messages, and broadcasts them to other connected clients. These messages are UI notifications, not durable synchronization; they must not become authorization evidence. Replace with WSS and branch-scoped authorization or remove the socket in favor of authorized server-sent events.

### 2.3 Caller inventory

The generic `Database` abstraction routes both reads and writes, so the SQL endpoints have a wide caller surface. Direct `DatabaseHelper.instance.database` consumers include:

- Orders/POS: `screens/pos_screen.dart`, `screens/waiter_order_screen.dart`, `screens/orders_history_screen.dart`.
- KOT/KDS and live views: `screens/kitchen_screen.dart`, `screens/live_tracking_screen.dart`, `screens/overview_screen.dart`, `screens/analytics_screen.dart`.
- Tables/reservations: `screens/tables_screen.dart`, `screens/admin/components/table_configuration_dialog.dart`.
- Customer/catalog/inventory: `screens/customers_screen.dart`, `screens/products_management_screen.dart`, `screens/recipe_management_screen.dart`, `screens/inventory_screen.dart`.
- Identity/settings/bootstrap: `screens/login_screen.dart`, `screens/user_management_screen.dart`, `screens/splash_screen.dart`, and `repositories/product_repository.dart`, `table_repository.dart`, `restaurant_repository.dart`, `user_repository.dart`.

Additional direct SQL expressions exist in analytics, kitchen, waiter, POS, order history, table, recipe, product, and repository code. Replacing only a few repository methods would leave raw SQL callers active. The migration inventory must be generated and checked again at implementation time before routes are removed.

### 2.4 Direct Spring write paths

`BackendApiClient` sends authenticated HTTP requests to Spring. The client calls branch/business writes directly for:

- Orders, item changes, confirm/rounds, cancel/void, billing, and payment at `/api/v1/orders/**` (`OrderApiRepository`, POS, waiter and order-history flows).
- KOT status/priority/chef operations at `/api/v1/kitchen-tickets/**`.
- Tables/floors and table status/assignment at `/api/v1/tables/**` and `/api/v1/floors/**`.
- Reservations at `/api/v1/reservations/**`.
- Customers, products, inventory, branches, and staff through their `/api/v1/**` controllers.
- Settings and user/session/auth operations through dedicated endpoints.

The order controller has method-level authorities such as `order:create`, `order:update`, `payment:record`, and `bill:generate`; the KOT, table, reservation, customer and inventory controllers similarly declare permissions. `SecurityConfig` enables method security, but its HTTP request matcher ends with `anyRequest().permitAll()`, so route-level safety depends on every handler being correctly annotated. `BranchScopeInterceptor`, `BranchScopeBodyAdvice`, and `BranchScopeService` enforce tenant membership and assigned branch scope on a set of branch resources; they do not apply to Shelf, and they are not a replacement for method permission checks.

`ApiMode` is not sufficient to prevent bypass. Native workflows can choose either SQLite/Shelf or Spring; Web forces backend mode in repository helpers while `DatabaseHelper` still returns `NetworkDatabase`. For Phase 3B, operational writes must have one enforced policy independent of user preference. Cloud APIs may remain for cloud-owned catalog/admin operations, but order, KOT, table occupancy, reservations, payment, and stock movements must not mutate PostgreSQL directly from branch terminals.

## 3. Authentication audit

| Mechanism | Current implementation | Limitation for Edge |
|---|---|---|
| Spring user login | `/api/v1/auth/login` validates tenant, active user, password, lockout, optional device and tenant; audit records success/failure. Password service uses Argon2 with BCrypt compatibility. | This authenticates against cloud while reachable; it is not Shelf middleware or an offline grant. |
| User JWT | `JwtService` signs HS256 with a configured secret or RS256 if a key pair is configured; access expiry defaults to 900000 ms. Claims include username subject, user ID, role(s), tenant ID, branch IDs, device ID, permission version, `iat`, and `exp`. | Tokens currently lack explicit issuer and audience claims and a token-purpose claim; default signing can be HMAC. Shelf has no verifier and cannot safely share the HMAC secret. |
| Spring JWT filter | Verifies the signature and claims, reloads active user/tenant/device and branch assignments, checks role/permission version, and rejects invalid scope. | Requires Spring/database access; a disconnected Shelf host cannot call it. |
| Role authorization | Spring `@EnableMethodSecurity` and controller `@PreAuthorize` use role/permission authorities. Permission values are derived from backend role policy. | Shelf does not resolve roles or permissions. UI role checks are not a server authorization control. |
| Device activation | An authorized `device:activate` user creates a one-use random activation code; the backend stores a hash and 15-minute expiry. Public activation consumes it and creates a generic `AuthDevice` with tenant/branch, but no device key, Edge-host type, credential, or one-active-host-per-branch rule. Login may attach the device ID. | Reuse as a lifecycle starting point, but activation is not proof of host-key possession or an Edge service credential. |
| Refresh/revocation | Refresh token is random, stored hashed, expires after 7 days, rotates on use, and reuse revokes the active family. User logout and device revoke endpoints revoke refresh tokens; access tokens are short-lived and checked against current user/permission/device state by Spring. | No Edge-host credential refresh/revoke protocol exists; disconnected hosts cannot receive immediate cloud revocation. |
| Secure Flutter storage | `AuthTokenStorage` uses `flutter_secure_storage` for user access/refresh tokens. `sendNetworkRequest` also falls back to legacy SharedPreferences token keys. | The fallback can put a bearer credential in weaker storage. Host device private keys are not currently provisioned/stored. |
| Native/local login | In local mode, the Flutter login queries the local `users` table. `DatabaseHelper.hashPassword` is plain unsalted SHA-256. UI stores username/role and a base64-encoded username/timestamp string in SharedPreferences as a “token”. | The value is not a signed/authenticated token. A LAN client can query the host database through Shelf and Shelf ignores the bearer value. Offline local login exists, but it does not establish a trusted Edge API principal. |
| PIN | Spring manager PIN is a 6-digit online verification, with five failures leading to a 15-minute lock. | It is not an offline user-auth protocol and should not be repurposed as one without approval. |

The login screen's missing-preference default and `UserRepository`'s default disagree. This is a routing defect that can make first-run behavior differ by the caller. Phase 3A design must make backend/local/Edge-session mode explicit; the implementation must not treat a preference as a credential.

## 4. Proposed trust model

### 4.1 Identities and authority

- **Edge host identity:** one registered device UUID and asymmetric public key, bound to one tenant and branch. The server holds the private key in an OS-protected store. Cloud identity state is authoritative. `auth_devices` currently represents generic devices; an additive future migration must distinguish `EDGE_HOST` from user/client devices and enforce at most one active Edge host per branch.
- **LAN client identity:** a separately assigned device/session identity where the product needs per-terminal audit or revocation. A client is never trusted because it is on the branch subnet or knows a numeric branch ID.
- **User identity:** Spring user account and role/permission assignment remain authoritative. Edge authorization is a bounded signed snapshot for the specified branch while offline; the host checks that the user grant, client identity, branch, and current local host epoch match.
- **Branch identity:** cloud binding sets the branch once. The Edge API derives tenant/branch from its registered host state; payload branch values are rejected or treated only as consistency assertions.
- **Guest identity:** table QR or digital-menu order must use a separate, short-lived, table-session capability. It cannot call staff APIs or raw SQL. Public menu read remains separate.

### 4.2 Credential issuance and token types

Do not reuse one bearer token for user login, Edge host identity, and synchronization. Proposed token classes:

1. **Cloud user access token:** preserve existing Spring contract for compatibility. For new Edge-aware tokens, add explicit issuer, audience, purpose, token ID, key ID, and strict expiry. Do not change existing claims or signing globally until downstream consumers have been inventoried.
2. **Edge user grant:** cloud-signed asymmetric token for `aud=dinemaster-edge-api`, containing user subject, tenant, one branch UUID/ID, Edge host UUID, client device/session ID, allowed permissions, permission version, host epoch, issued-at/not-before/expiry, and `jti`. The Shelf validates signature against public keys, issuer/audience/purpose, time window, host binding, epoch, and exact permission scope. No HMAC secret is copied to Flutter.
3. **Edge host sync credential:** host proof-of-possession key plus a short-lived cloud token scoped to `aud=dinemaster-sync`, one host, tenant, branch, epoch, and push/pull operations. It is not accepted on user domain APIs. Phase 3A designs it only; automatic sync remains disabled.
4. **Refresh/renewal credential:** opaque high-entropy rotating secret stored as a hash by the cloud and in host OS-protected storage. Reuse revokes the token family and host registration as configured. A renewal requires host key proof. It cannot silently change branch or epoch.

Use the existing `JwtService`/JWKS capability where compatible, with an explicit Edge token purpose and asymmetric signing keys. Existing default HS256 support remains for existing sessions; Edge verification must never require exposing the HMAC secret to Shelf. Use `kid` and overlapping public-key rotation windows; retain the old public key only until all outstanding grants expire.

### 4.3 Secure activation, one-host policy, and rebind

1. A tenant owner or operator with a new dedicated permission requests a branch activation code. The cloud confirms tenant/branch ownership and that no other active host is registered. The code is random, single-use, short-lived, stored as a hash, rate-limited, and displayed once.
2. The Edge host generates its key pair locally. Activation requires the code plus a nonce/signature proving possession of the matching private key. Cloud records device type, key thumbprint, tenant, branch, epoch, software version, and activation audit.
3. Cloud issues the host certificate/refresh registration only after binding. It returns the canonical branch identity and epoch. A failed or replayed activation is denied and audited.
4. To rebind or replace a lost host, an authorized online operation first revokes the old host and credentials, then advances the branch epoch, then issues a new activation. Never permit offline self-rebind. A stale host may continue local work while isolated only for the approved offline grace; it must be fenced from cloud sync and other active hosts.
5. The one-active-host rule needs a server-side uniqueness/lease invariant and a takeover protocol. A second host must not be allowed to activate just because the first is temporarily unreachable; failover requires an explicit server-authorized fencing generation.

The existing 15-minute generic activation code is useful but insufficient on its own: no Edge type, key possession, active-host uniqueness, host credentials, or epoch reset is currently represented by that activation flow.

### 4.4 Expiry, refresh, revocation, and offline authorization

**Proposed policy for owner review:** user access tokens live 15 minutes; rotating refresh credentials have a 7-day maximum lifetime, matching current Spring refresh lifetime; an Edge user grant is limited to one work shift (proposed 12 hours) and cannot be renewed without the cloud; an Edge host's sync access token is short-lived and renewed using key proof. These numbers are recommendations, not approved policy.

- Shelf rejects a grant if signature, issuer, audience, `nbf`, `exp`, host ID, tenant, branch, device/session, permission version, epoch, or scope fails.
- When cloud is reachable, the Edge refreshes the signed authorization snapshot after login/permission change and receives current revocation/host-binding state. Refresh-token reuse, user deactivation, permission change, device revoke, tenant suspension, branch rebind, or epoch increment invalidates affected grants on the next cloud contact.
- When cloud is offline, Shelf accepts only a not-expired signed grant cached through secure client storage. Revocation cannot be instantaneous during a partition. The maximum offline authorization period and whether already-open sessions can continue past expiry are product/security choices; recommended default is deny new sessions and high-risk operations at expiry while preserving read-only/local recovery access.
- Do not mint offline grants from the local base64 string, UI role, or unsalted local hash. The current local login behavior needs a reviewed replacement. If local offline password verification is retained, migrate to a salted, memory-hard verifier with rate limit/lockout and audited rehash; do not sync the cloud password verifier to arbitrary LAN clients.
- Account for wall-clock rollback. Grants need bounded clock skew and Edge monotonic/last-trusted-time tracking; a restored/stale epoch does not regain authority by changing local time.

### 4.5 TLS and LAN client protection

- Serve the Edge API as HTTPS; serve notifications as WSS. Do not expose a plaintext compatibility port after cutover.
- Provision a host server key and certificate with SAN for a stable local DNS name. A trusted pairing step installs or pins the correct trust anchor/certificate fingerprint on enrolled clients. Reject certificate-name mismatch; do not trust a user-entered IP as server identity.
- Automate renewal and rotation before expiry. The recovery path for offline certificate expiry must not disable certificate checks; use a locally approved re-pair/recovery procedure.
- Require current TLS; whether mutual TLS is required for managed terminals is an approval item. Bearer authorization remains required even with TLS or network segmentation.
- Apply request/body size limits, content-type checks, rate limits for login/activation, replay protection for mutating requests, safe error responses, security audit events, and secret redaction.
- Bind the gateway to the selected LAN interfaces only when practical and use host firewall rules; network isolation reduces exposure but never replaces identity checks.

### 4.6 Backup restore and epoch protection

Backup restore is a control-plane action, not a local preference. A restored host starts quarantined: it may inspect/recover local data but cannot push or claim the prior active host lease. An authorized cloud operation validates host, tenant, branch, backup marker, and operator; increments the branch epoch/fencing generation; revokes old grants/cursors; issues a one-use signed reset result; and requires reviewed reconciliation plus an explicit resume. A copied database alone cannot reset its epoch. Do not delete or overwrite pending data as part of reset.

## 5. Authenticated LAN API boundary

The Edge API has a single middleware chain before route dispatch:

1. Validate TLS and protocol/client version.
2. Validate bearer signature, issuer, audience, purpose, and expiry.
3. Load/check Edge host registration and one-branch binding; compare token epoch with local active epoch.
4. Resolve user and device/session; validate tenant, branch, permission version, account status and offline lease.
5. Authorize the operation against a specific permission (for example `order:create`, `payment:record`, `kot:update_status`).
6. Validate DTO schema, length/range/enum constraints and resource references; derive tenant/branch instead of trusting request values.
7. Enforce idempotency on every retryable mutation and write an audit record in the same SQLite transaction where applicable.

The API contract is in `M28_EDGE_API_CONTRACT.md`. A route not explicitly registered in the Edge router is denied; generic table/SQL parameters are not accepted.

## 6. Public routes, Web, and cloud-write ownership

- `/api/menu` and `/menu` can remain public only for intended non-sensitive menu content, with cache and data-field allowlists.
- Existing `/api/order` is not treated as an authenticated staff route. Preserve digital-menu behavior through a distinct QR/table-session capability, payload limits, replay/idempotency, and abuse controls. Decide whether guest ordering is enabled per branch.
- Flutter Web currently reaches `NetworkDatabase` on Shelf port 8082 when it uses `DatabaseHelper`, even though `ApiMode` forces backend mode for some repositories. Phase 3A must remove this mixed trust path. Target Web is online-only; cloud-owned administration/reporting may use Spring. A branch operational mutation is disabled unless a reviewed secure Edge routing/relay path is available. Web may not directly write operational rows in PostgreSQL and thereby bypass the Edge host.
- Direct Spring write endpoints remain protected by Spring JWT and method authorities where annotated, but need an explicit branch-terminal policy. Phase 3A must classify each operational endpoint as Edge-only, cloud-admin-only, public guest, or read-only and enforce that classification before the Phase 3B client cutover.

## 7. Development prerequisites vs production rollout prerequisites

### Development prerequisites

- Product/Security/Operations/Finance approve or amend the policy checklist.
- Threat model and token formats, issuer/audience, permissions, offline lease duration, TLS trust bootstrap, host takeover, and epoch reset are signed off.
- Additive schema/API design is reviewed; no V34 or earlier Flyway migration is edited.
- Define feature/protocol version, error responses, observability without logging tokens/PII, test keys, and isolated test environments.
- Inventory every call site again and make a traceable compatibility matrix before coding.

### Production rollout prerequisites

- All production Edge hosts and clients are inventoried and backed up; real tenant/branch ownership and UUID mappings have been verified and approved.
- Production key custody, host OS support, TLS CA/trust bootstrap, certificate rotation, key recovery, credential rotation/revocation, and operator access are operational.
- A controlled upgrade sequence exists for Edge hosts, LAN clients, Web deployments, Spring routes, and reverse proxies; no unauthenticated overlap is permitted.
- Offline expiry, host replacement, branch transfer, backup restore, emergency revocation, clock skew, outage, and stale-client cases have been rehearsed.
- Pilot branch passes security review and audit verification; support can diagnose a rejected/expired token without instructing staff to disable TLS or reopen SQL routes.
- Production enablement is a separate approval after tests; no sync feature flag is enabled by this design.

## 8. Phase 3A exit criteria

Phase 3A is **not approved to implement yet**. After policy approval, its implementation can be considered complete only when:

- The Shelf boundary validates user/device/host identity, tenant/branch, role/permissions, expiry, epoch, and payload for every protected operation.
- One active authorized Edge host per branch is enforced with server fencing; rebind, credential rotation/revocation, and backup epoch reset are audited and tested.
- LAN HTTP/WS operational routes are HTTPS/WSS and public/guest operations have separate explicit capabilities.
- No `/api/db/*` endpoint permits unauthenticated access; no direct cloud write path can bypass Edge ownership for branch operations.
- Local offline login no longer treats unsalted SHA-256 or a base64 string as an authorization credential; offline session behavior matches approved expiry policy.
- Old clients fail explicitly with an upgrade-required response; no anonymous SQL fallback exists.
- Security tests cover replay, stolen/expired/revoked tokens, branch/tenant substitution, role denial, epoch rollback, wrong-host, TLS mismatch, rate limit, body abuse, and legacy protocol access.

## 9. Sources inspected

- Flutter: `frontend/lib/services/server_service_io.dart`, `sync_service.dart`, `auth_token_storage.dart`, `backend_api_client.dart`, `repositories/user_repository.dart`, `repositories/order_api_repository.dart`, `core/database/network_database.dart`, `network_http_io.dart`, `network_http_web.dart`, `core/database/database_helper.dart`, `core/api/api_mode.dart`, `screens/login_screen.dart`.
- Spring: `config/SecurityConfig.java`, `JwtAuthenticationFilter.java`, `SecurityBeansConfig.java`, `security/BranchScopeService.java`, `BranchScopeInterceptor.java`, `BranchScopeBodyAdvice.java`, `auth/controller/AuthController.java`, `auth/service/AuthService.java`, `JwtService.java`, auth-device/activation/refresh entities, and order/KOT/table/reservation/customer/inventory controllers.
- M28 planning/Phase 2 docs: all five existing planning documents, `M28_PHASE_2_IMPLEMENTATION_REPORT.md`, and `M28_PHASE_2_FINAL_VALIDATION_REPORT.md`.
