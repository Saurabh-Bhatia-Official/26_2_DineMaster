# Backend Test Report

## Execution

- Command: `mvnw.cmd test` from `backend/`
- Runtime: Java 21
- Database: H2 in-memory database with the `test` profile; the production database is not used
- Latest verified run: **99 tests, 0 failures, 0 errors, 0 skipped** (see `backend/target/surefire-reports/`)
- Surefire XML and text reports: `backend/target/surefire-reports/`

## Coverage implemented

- **Authentication and authorization:** login success/failure, tenant mismatch, unavailable JWKS, rate limiting, manager PIN checks and lockout, role/permission fallback and version invalidation.
- **Controller integration:** authentication, health, product, order, customer, category, branch, branch hours, floor, table, reservation, and guest routes.
- **Protected read access:** anonymous requests to 51 protected GET routes are checked for rejection. These are authorization checks, not 51 full successful-response workflow tests.
- **Repository integration:** product persistence, tenant-scoped/custom query behavior, update and delete against H2.
- **Business-logic unit tests:** product behavior, FIFO inventory consumption, order state transitions and totals, recipe unit conversion, QR guest rate limiting, login rate limiting, manager PIN, and role permissions.
- **Application context:** Spring Boot startup with the test profile.

The test dependencies (JUnit 5, Mockito, Spring Boot Test, MockMvc, AssertJ, and Spring Security Test) were already available; no additional `pom.xml` dependencies were necessary. The test profile uses an in-memory database and disables the PostgreSQL-specific recipe deduction scheduler. Production scheduling remains enabled by default.

The branch-hours integration test exposed a persistence mapping mismatch: the entity's default enum mapping did not match the existing `VARCHAR(10)` migration column. `BranchHours.weekday` now explicitly persists enum names, matching that schema.

## Remaining coverage gaps

This is materially broader coverage, but **not exhaustive endpoint-by-endpoint functional coverage of every controller, service, repository, entity, and DTO**. Several route families still need successful and invalid-input workflow tests, including tenant/user administration, inventory and kitchen controllers, QR session success flows, recipe CRUD/deduction workflows, role management, menu availability, and dashboard data. The 51 protected-read checks establish anonymous-access rejection only; they do not prove each route's authenticated response behavior. No JaCoCo instrumentation is configured, so a line/branch coverage percentage is not available.

## Failures

The latest full-suite run had no failing, errored, or skipped tests.
