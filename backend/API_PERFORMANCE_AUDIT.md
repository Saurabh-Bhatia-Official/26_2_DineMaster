# DineMaster Backend API Performance Audit

**Audit date:** 2026-11-20  
**Scope:** `backend/src/main/java`, Spring configuration, Flyway migrations, and backend tests.  
**Status:** Source-level audit; no application/database load test was possible in this phase.

## Executive summary

The backend has 21 REST controller classes, 192 Java source files, 33 repository classes/interfaces (including the JDBC dashboard repository), and 31 Flyway migrations. It already has useful foundations: Spring Data JPA and JDBC, `spring.jpa.open-in-view=false`, Flyway-managed PostgreSQL schema, DTO mapping on order reads, branch-scoped indexes in later migrations, and pagination on the staff listing.

The largest source-evidenced performance risks are:

1. **Unbounded list reads:** order, product, customer, inventory, reservation, kitchen-board/history, tenant, and several other list endpoints load all matching rows. Some then filter and sort in application memory.
2. **Query-per-row and query-per-item paths:** menu construction, guest menu construction, kitchen board rendering, staff list DTO mapping, and order/cart item validation scale database round trips with response/request size.
3. **Repeated authorization lookups:** `BranchScopeService.requireBranch` reloads the user and branch and checks membership. Callers commonly already loaded the user and may make multiple branch-scope calls in one request.
4. **No Redis/cache or application metrics setup:** Redis/cache dependencies are absent; Actuator is absent and management endpoints are disabled by default.
5. **No measured database evidence:** SQL plans, production row counts, query timings, endpoint latency percentiles, cache hit rates, and connection utilization are unavailable. Index additions and connection-pool sizing therefore need staging/PostgreSQL evidence before finalizing.

No source-level issue proves which endpoint is currently slow in production. Findings below identify query growth and work amplification; ranking is based on likely impact, not measured latency.

## REST controller and endpoint inventory

The inventory records the mappings found in the controller source. Bracketed paths indicate the controller's common prefix. Aliases and HTTP-verb aliases are called out explicitly.

| Controller | Endpoint mappings |
|---|---|
| `HomeController`, `HealthController` | `GET /`; `GET /api/v1/health` |
| `AuthController` (`/api/v1/auth`) | `POST /devices/activation-codes`, `/devices/activate`, `/login`, `/super-admin/login`, `/refresh`, `/logout`, `/logout-all`, `/change-password` and `/password/change`, `/pin`, `/devices/{deviceId}/revoke`; `GET /jwks`, `/me`, `/me/permissions`, `/devices` |
| `UserController` (`/api/v1/admin/staff` and `/api/v1/users`) | `GET /`, `/{id}`, `/{id}/sessions`, `/{id}/audit`; `POST /`, `/{id}/deactivate`, `/{id}/reactivate`, `/{id}/reset-password`, `/{id}/sessions/revoke`; `PUT /{id}`, `/{id}/roles`, `/{id}/branches`; `PATCH /{id}/branches`; `DELETE /{id}`, `/{id}/sessions` |
| `TenantController` (`/api/v1/super-admin/tenants` and `/api/v1/tenants`) | `GET /`; `POST /`; `PATCH /{tenantId}/suspend`, `/{tenantId}/activate` |
| `RestaurantController` (`/api/v1/branches`) | `GET /`, `/{id}`, `/{id}/hours`; `POST /`, `/{id}/suspend`; `PUT /{id}`, `/{id}/hours` |
| `DashboardController` (`/api/v1/dashboard`) | `GET /summary`, `/revenue-trend` |
| `FloorController` (`/api/v1/floors`) | `GET /`; `POST /`; `PUT /{id}` |
| `TableController` (`/api/v1/tables`) | `GET /`, `/{id}`; `POST /`, `/{id}/qr-session`; `PUT /{id}`, `/{id}/status`, `/{id}/waiter`; `PATCH /{id}/status`; `DELETE /{id}` |
| `BookingController` (`/api/v1/reservations`) | `GET /`; `POST /`; `PUT /{id}`; `PATCH /{id}/status`; `DELETE /{id}` |
| `CategoryController` (`/api/v1/categories`) | `GET /`, `/{id}`; `POST /`; `PUT /{id}`, `/reorder`; `DELETE /{id}` |
| `ProductController` (`/api/v1/products`) | `GET /`, `/all`, `/{id}`, `/category/{category}`; `POST /` (JSON or multipart), `/{id}/image`, `/{id}/archive`; `PUT /{id}`, `/{id}/availability`; `PATCH /{id}/availability`; `DELETE /{id}` |
| `MenuController` (`/api/v1`) | `GET /menu`, `/branches/{branchId}/availability`; `PUT /branches/{branchId}/availability` |
| `CustomerController` (`/api/v1/customers`) | `GET /`, `/{id}`; `POST /`; `PUT /{id}`; `DELETE /{id}` |
| `InventoryController` (`/api/v1/inventory`) | `GET /`; `POST /`; `PUT /{id}`; `PATCH /{id}/stock`; `DELETE /{id}` |
| `OrderController` (`/api/v1/orders`) | `GET /`, `/parked`, `/{id}`, `/{id}/items`, `/{id}/timeline`, `/{id}/bill`; `POST /`, `/{id}/items`, `/{id}/discount`, `/{id}/park`, `/{id}/reopen`, `/{id}/request-bill`, `/{id}/confirm`, `/{id}/rounds`, `/{id}/accept-qr`, `/{id}/hold`, `/{id}/cancel`, `/{id}/void`, `/{id}/bill`, `/{id}/payment`; `PUT /{id}/items`, `/{id}/customer`; `DELETE /{id}/discount` |
| `KotController` (`/api/v1/kitchen-tickets`) | `GET /`, `/history`, `/events`, `/{kotId}/delays`, `/order/{orderId}`; `PUT /{kotId}/items/{itemId}/status`; `POST /{kotId}/priority`, `/{kotId}/delay-reason`, `/{kotId}/assign-chef` |
| `M13KotController` (`/api/v1`) | `GET /kots/{id}`, `/orders/{id}/kots`, `/print-jobs/failed`; `POST /kots/{id}/reprint`, `/print-jobs/{id}/retry` |
| `RecipeController` (`/api/v1/recipes`) | `GET /`, `/{productId}`, `/{productId}/cost`; `PUT /{productId}` |
| `GuestController` | `GET /guest/menu`, `/guest/orders/{id}`, `/api/v1/guest-service-requests`; `POST /guest/orders`, `/guest/call-waiter`, `/api/v1/guest-service-requests/{id}/acknowledge` |
| `RoleController` (`/api/v1/roles`) | `GET /`, `/{roleCode}`, `/permissions`; `POST /`; `PATCH /{roleCode}/permissions`; `PUT /{roleCode}/permissions` |

Two advice classes also participate in request processing: `ApiExceptionHandler` and `BranchScopeBodyAdvice`.

## Findings

### High priority: query amplification and unbounded reads

| Area / endpoint | Source evidence and current behavior | Performance risk | Direction |
|---|---|---|---|
| Menu: `GET /api/v1/menu` | `MenuController.menu` queries categories, then products separately per category, then calls `findByBranchIdAndProductId` once per product. It also constructs and hashes the complete response before returning `304 Not Modified`. | Roughly one product query per category plus one branch-configuration query per product; work remains full even on conditional requests. | Replace nested repository access with a branch/category/product projection and left join; preserve ETag/response contract and compute the validator from the projected representation. Cache the resulting branch-scoped DTO only after all write paths invalidate it. |
| Guest menu: `GET /guest/menu` | `GuestOrderingService.menu` retrieves each branch-product configuration once in the filter and again in the mapper. | Up to two configuration queries per product, in addition to the base product query. | Reuse one joined projection shared with the authenticated menu read model; do not cache guest tokens or order state. |
| Kitchen board/history: `GET /api/v1/kitchen-tickets` and `/history` | `KotQueryService.board` loads every KOT for the branch and filters/sorts in Java. `toKitchenCard` separately loads order items and KOT item snapshots per ticket; maps snapshots back to lines using a linear scan for each item. | At least two queries per ticket plus possible lazy association loads; full branch history, in-memory sorting/filtering, and quadratic item matching. | Select active-board/history rows and item data in bounded projections; push station/status/time filters and ordering into SQL; use page/continuation limits for history. |
| Legacy KOT details: `GET /api/v1/orders/{id}/kots` | `M13KotController.byOrder` maps each KOT through `toResponse`, which queries KOT items per row. | Query count grows with KOT count in one order. | Fetch all KOT and item rows in one/two bulk queries and group by KOT ID. |
| Staff list: `GET /api/v1/admin/staff` and `/api/v1/users` | `UserController.list` is paginated, but for each row calls `RolePermissionService.permissionsFor(...)` and `UserBranchRepository.findBranchIds(...)`. | Per-page query amplification; role grants are mostly repeated for identical roles/tenants. | Load role grants once per page/request and branch assignments for all page user IDs in one query; map to DTOs in a read-only transaction. |
| Orders list: `GET /api/v1/orders` | `OrderController` calls `OrderService.getOrdersByRestaurant` for each selected branch. The service first checks branch existence and then fetches the complete order list; waiter filtering occurs in memory. | Unbounded rows, an avoidable existence query per branch, and application-side filtering across all orders. | Add a branch/status/waiter-aware pageable query and a stable sort; preserve a compatible legacy response path or introduce pagination compatibly. |
| Product reads: `GET /api/v1/products`, `/all`, `/category/{category}` | Product repository methods load all non-deleted products for a tenant/branch; optional `available`, `categoryId`, and text search are applied in Java. | Large reads, extra serialization, and scans of unrelated rows for category/search filters. | Push all filters and sorting into the repository query; introduce bounded pagination for management/search while preserving legacy API behavior. |
| Inventory, customer, reservation, parked carts, pending service requests, failed print jobs, tenant list, KOT history | Corresponding controller/service methods return `List` results with no page/range bound. Examples: `InventoryController.list`, `CustomerController.getAllCustomers`, `BookingController.list`, `CartService.parked`, `GuestOrderingService.pendingCalls`, and `PrintJobService.failed`. | Response time, memory, and payload size grow with retained records. | Prioritize history/transactional lists for pagination or bounded windows; choose response compatibility strategy before changing envelopes. |

### Other repeated database work

- **Order/cart item loops:** `OrderService.createOrder`, `OrderService.confirmDraft`, `CartService.newLine`, and `GuestOrderingService.submit` resolve products and branch-specific availability/pricing individually. Guest orders are capped at 20 total items and carts at 100 distinct lines, but request-time database calls still grow with item count. Bulk-load products/configurations by requested IDs, then preserve per-item validation and pricing rules.
- **Cart response duplication:** `CartService.createDraft` calls `replaceItems`, which builds a `CartResponse` by querying its order items; that response is discarded and `createDraft` builds another response. Other mutations also reload item rows for response construction after already loading/saving them. Reuse the in-memory rows where safe.
- **Recipe writes:** `RecipeService.save` fetches each ingredient separately and inserts each `RecipeItem` separately. Resolve ingredient IDs in one tenant/branch-scoped query and use `saveAll`.
- **Authentication and authorization reads:** `BranchScopeService.requireBranch` calls `requireUser`, `belongsToTenant` (loads a branch), and an assignment existence query. Controllers often separately call `requireUser`, and some make more than one `requireBranch` call. Avoid repeated in-request lookups using a request-scoped authenticated principal/context and narrowly scoped existence projections; do not introduce cross-request caching of authorization decisions without revocation semantics.
- **Repeated branch checks across MVC layers:** `BranchScopeInterceptor` is registered for `/api/v1/**`, and `BranchScopeBodyAdvice` also checks branch ownership for applicable request bodies. Controllers then call `requireBranch` again. For scoped reads/writes, this can repeat user, branch, and assignment queries two or more times in one request. Preserve the security boundary, but make one shared request-scoped authorization decision available to interceptor/advice/controller code rather than independently repeating database checks.
- **Role/user branch mapping:** paginated staff DTOs repeat permission and branch reads per row. `RolePermissionService.replacePermissions` also calls `users.findAll()`, filters in application code, then saves matching users one by one.
- **Token revocation loops:** logout-all, refresh-family/device revocation, and tenant suspension load tokens/devices and save each entity separately. Evaluate bounded bulk updates within the existing transaction while preserving audit and revocation behavior.
- **Small redundant calls:** `CustomerController.delete` checks tenant ownership with `findById`, then calls `existsById`, then `deleteById`; `OrderService.getOrdersByRestaurant` performs an existence query before its read query. These can be collapsed into scoped deletes/queries or removed where response semantics permit.
- **Repeated order/resource reads:** most `OrderController` item routes first call `getOrderRestaurantId` (a scalar query) for scope, then fetch the order again for response/authorization or service work. The `/items` route additionally checks `existsById` before selecting items. `RecipeController.legacyGet` calls its own `get` handler, then executes the recipe read again to build the legacy response. Consolidate each into one scoped read model while retaining authorization and output shape.
- **Dashboard:** summary issues independent revenue, active-table, occupied-table, and optional low-stock queries after branch/user scope checks. These are aggregate queries, not entity N+1s. Combine table counts into one aggregate; retain the revenue query's branch/date bounds. `revenue-trend` already aggregates the date range in SQL and has a 1–31 day guard.

## ORM, payload, and transaction observations

- All discovered `@ManyToOne`/`@OneToOne` mappings are explicitly `LAZY`; the notable eager relationship is `Role.permissions` (`@ManyToMany(fetch = EAGER)`). There are no broad entity collection mappings in the examined source, reducing the usual collection-serialization N+1 risk.
- `spring.jpa.open-in-view=false` is a good boundary. `JacksonConfig` disables forced lazy loading and serializes identifiers for uninitialized proxies. Several endpoints still return JPA entities directly (products, categories, branches, tables, bookings, inventory, floors, tenants); DTO/projection responses are safer for payload stability and query control, but changing those response shapes must be avoided or versioned.
- Order APIs already use `OrderResponse`/`OrderItemResponse` DTOs. `OrderController` list APIs return `List` and have no bounds; DTO use alone does not bound data retrieval.
- Write flows generally have transaction boundaries in services/controllers and several use explicit pessimistic locks/optimistic versions. Multi-query read-model construction should use deliberate `@Transactional(readOnly=true)` boundaries. Avoid changing isolation/locking or moving work outside transactions without concurrency tests.
- `hibernate.format_sql=true` is configured. It is not evidence that SQL is currently emitted, but formatted SQL logging should remain disabled in production unless temporarily diagnosing a problem.

## PostgreSQL and index review

Flyway already adds a broad set of useful indexes, including:

- Orders: branch/status/time, branch/time/status (dashboard), parked drafts, idempotency, and unique branch/order number.
- Order items: order and product IDs.
- Product/category: category ID, tenant/deleted/availability, tenant SKU; branch-product composite primary key and reverse product index.
- Staff branches: `(user_id, branch_id)` primary key and `(branch_id, user_id)` reverse index; employee code and role-related constraints.
- Bookings and inventory: branch/time and restaurant indexes.
- Kitchen: KOT order ID, branch/kind/created time, KOT items, print-job branch/status/time, kitchen event replay, delays.
- QR sessions/service requests, recipe/stock queues, audit timelines, waiter assignments, and invoices.

Potential index/query mismatches to validate with PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` on representative data:

1. Product menu/list predicates combine branch, category, availability, deletion state, and sorting; existing product indexes do not clearly cover the complete branch/category/active read shape.
2. Staff search uses `lower(column) LIKE '%term%'` across name/username/contact/employee code/email, plus tenant, role, active and branch filters. Ordinary B-tree indexes do not accelerate leading-wildcard search. Consider `pg_trgm` GIN indexes or a different search contract only after measuring selectivity/write overhead.
3. Customer-by-tenant listing has no obvious tenant/time composite index in the reviewed migrations; `customers(tenant_id, created_at DESC)` is a candidate if this list is retained.
4. Category listing filters tenant/deleted and sorts by sort order/ID; check if a composite index is warranted at observed category scale.
5. KOT board currently filters by `Order.restaurant_id` and sorts on KOT creation time rather than using its `branch_id` directly; rewrite the query before considering an index. Existing branch/KOT and order/KOT indexes may be sufficient for the rewritten plan.
6. User counts and several normalized status predicates apply `UPPER(...)`; confirm whether these prevent use of current indexes before adding functional indexes or normalizing stored values.

Do not add all candidates mechanically. Validate query plans, selectivity, row counts, index size, and write cost on PostgreSQL. Migration `V19__dashboard_branch_date_index.sql` already provides a branch/time/status index for the dashboard's bounded date query.

## Redis caching assessment

There is currently no Redis dependency, Spring Cache configuration, cache annotation, Redis environment configuration, or cache invalidation logic.

| Candidate | Assessment |
|---|---|
| Branch menu / guest menu | Strong read-heavy candidate. Cache only branch-scoped DTOs; ensure product, category, tax/menu availability, branch override, archive/delete, and category reorder writes invalidate the correct branch key. Keep ETag semantics. |
| Categories / stable branch configuration | Potential candidate if read volume justifies it. Tenant/branch keys and mutation invalidation are mandatory; do not cache authorization results. |
| Branch details/hours | Potential candidate with longer TTL, evicted after branch or hours mutations. |
| Dashboard aggregates | Possible only with a short TTL and an explicit consistency decision. Order/payment, table, and inventory writes are spread across multiple services, so do not enable until invalidation coverage and acceptable staleness are tested. |
| Orders, payments, stock, reservations, sessions, auth data, guest service calls | Do not cache initially; these are mutable, operational, security-sensitive, or real-time data. |

Use cache DTOs rather than Hibernate entities, explicit cache names and branch/tenant keys, environment-based Redis settings, per-cache TTLs, and an error handler that logs Redis cache failures while allowing the database-backed read to proceed. Cache invalidation must occur only after a successful transaction commit. Measure hit/miss and stampede behavior before adding locking or more elaborate cache mechanics.

## Configuration and observability gap

- `application.yml` uses environment-substituted DB/JWT/cloud settings and Flyway, but has no explicit `spring.datasource.hikari.*` sizing/timeouts or validation from observed pool demand. Spring Boot's default pool is HikariCP; defaults are not workload tuning.
- `spring-boot-starter-actuator`, a Micrometer registry, and Redis dependencies are absent from `backend/pom.xml`.
- `management.endpoints.enabled-by-default=false`; there is no explicit Actuator exposure configuration.
- No source configuration was found for HTTP latency histograms, SQL execution-time metrics, slow-query thresholds, Redis hit/miss metrics, or load-test tooling.
- If observability is added, expose only required endpoints (health/info and an authenticated/internal metrics endpoint), use bounded metric tags, and never tag metrics with user IDs, raw paths, SQL values, or other high-cardinality/sensitive data. HTTP metrics can cover endpoint latency/error rate; Hikari metrics cover pool usage; database statement timing needs explicit instrumentation or a supported datasource/ORM observation path.

## Tests and measurement limits

- Backend tests exist for controller flows and selected service/repository logic. `application-test.yml` uses H2, disables Flyway, and creates a test schema; it does not validate production Flyway migrations or PostgreSQL query plans.
- No Redis/cache integration tests, PostgreSQL Testcontainers configuration, query-count/N+1 tests, or repeatable load-test report was identified in the inspected test/resource setup.
- A baseline test run was attempted with `backend/mvnw.cmd test`, but the Maven wrapper stopped before compilation because `JAVA_HOME` is not defined in this environment.
- No Neon/PostgreSQL connection or production-like dataset was available for query plans or load tests. Therefore no measured before/after latency, throughput, pool size, or cache hit rate is claimed by this audit.

## Proposed implementation order

1. Establish a runnable baseline and staging PostgreSQL measurements; capture query counts/timings and load-test p50/p95/p99, throughput, error rate, and pool utilization.
2. Optimize high-impact query shapes without changing response contracts: joined menu projections, bulk staff branch/role mapping, set-based cart/order product loads, and batched KOT read models.
3. Add database-level filtering and bounded pagination for high-growth lists, resolving the Flutter/API compatibility strategy before changing list response semantics.
4. Add only query-plan-justified Flyway indexes and production-safe Hikari configuration.
5. Add Spring Cache/Redis configuration and cache only validated read-mostly DTOs; test transaction-aware invalidation and Redis outage fallback.
6. Add Actuator/Micrometer instrumentation, repeatable staging load tests, and the remaining implementation documentation.
7. Run backend tests, PostgreSQL migration/integration tests, cache invalidation/outage tests, and Flutter/Postman contract checks; compare against the baseline.

