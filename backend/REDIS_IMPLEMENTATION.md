# Redis caching

## Architecture

Spring Cache uses Spring Data Redis and the Spring Boot-managed Lettuce client. Redis cache values are JSON-serialized cache DTOs with a serializer configured per cache; Hibernate entities and authentication/authorization decisions are not cached. Cache errors are logged and handled so cache reads fall through to the database and cache writes/evictions do not fail the request.

The guest menu resolves and validates the QR session on every request. Only the branch-scoped menu read model is cached; the table label and session/token state are assembled per request.

## Setup

Start a local Redis instance on `localhost:6379`, or set the following environment variables for another Redis deployment:

| Variable | Default | Description |
|---|---:|---|
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | empty | Redis password; do not commit credentials |
| `REDIS_SSL_ENABLED` | `false` | Enable TLS for managed/production Redis |

The application defaults to a local profile. Tests use an in-memory cache manager and do not require a Redis server. Redis connection failures at runtime leave database-backed endpoints available.

## Cache keys and TTLs

Keys are namespaced to prevent cross-tenant/branch reuse:

| Cache | Key format | TTL |
|---|---|---:|
| `branch-catalog` | `tenant:{tenantId}:branch:{branchId}` | 2 minutes |
| `guest-menu` | `tenant:{tenantId}:branch:{branchId}` | 2 minutes |
| `branch-availability` | `tenant:{tenantId}:branch:{branchId}` | 2 minutes |
| `categories` | `tenant:{tenantId}` | 10 minutes |
| `category-details` | `tenant:{tenantId}:category:{categoryId}` | 10 minutes |
| `branch-details` | `tenant:{tenantId}:branch:{branchId}` | 10 minutes |
| `branch-hours` | `tenant:{tenantId}:branch:{branchId}` | 10 minutes |

The branch menu, guest menu projection, and branch availability projection are cached independently, so each endpoint populates only the read model it needs. `sync=true` uses the cache provider's synchronized value loading to reduce same-key concurrent misses.

## Endpoints covered

- `GET /api/v1/menu`
- `GET /guest/menu` (menu projection only; guest session data is never cached)
- `GET /api/v1/categories`
- `GET /api/v1/categories/{id}`
- `GET /api/v1/branches/{id}`
- `GET /api/v1/branches/{id}/hours`
- `GET /api/v1/branches/{branchId}/availability`

Authorization and branch access checks remain on the request path. Caller-dependent `GET /api/v1/branches` is intentionally not cached.

## Invalidation

Evictions are registered after commit when a transaction is active; otherwise they run after the successful repository write.

- Product create/update/image/availability/archive/delete evicts that branch's catalog cache. A product branch move evicts both old and new branch keys.
- Category create/update/delete/reorder evicts the tenant category list/detail and all tenant branches' catalog keys.
- Branch menu availability updates evict the affected branch catalog.
- Branch update/suspend evicts branch details; hours updates evict branch hours.

If a Redis eviction fails, the failure is logged and bounded TTLs limit staleness. Tenant-wide catalog eviction enumerates active tenant branch IDs from PostgreSQL; if enumeration fails, the warning is logged and existing entries expire by TTL.

## Verification and performance results

Automated verification: the backend suite passed **102 tests** (0 failures/errors) on Java 21 with the H2 test profile and in-memory cache manager. Coverage includes cache DTO JSON round-tripping, category/product/branch detail/hours invalidation, guest/menu/availability cache invalidation, and cache read-error handling. These tests do not represent Redis network latency.

No before/after endpoint timings, Redis hit timings, query-count reduction, or production performance improvements are claimed. A representative benchmark requires a running Redis instance and a seeded PostgreSQL dataset. Record baseline, first miss, repeated hit, database query count, invalidation behavior, and Redis-unavailable behavior in that environment before drawing performance conclusions.

## Production deployment

Provide `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, and `REDIS_SSL_ENABLED=true` through the deployment platform's secret/configuration mechanism. Restrict Redis network access to application workloads, require authentication and TLS where supported, and monitor Redis availability/latency plus application cache error warnings. Keep database access configured as the source of truth; Redis is an optional cache and should not be required for application startup or database-backed request handling.
