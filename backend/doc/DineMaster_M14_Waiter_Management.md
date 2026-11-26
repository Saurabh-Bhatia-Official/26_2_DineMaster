# DineMaster — M14: Waiter Management

**Module:** M14  
**Module type:** EDGE, ID  
**Primary user:** Waiter  
**Document type:** Module-level technical specification  
**Source:** DineMaster Master Technical Documentation, Module M14; related Table, Order, Menu, Kitchen and Notification contracts

> **Source discipline:** This specification preserves the M14 requirements and identifiers from the master documentation. Implementation guidance added to make the module actionable is marked **[PROPOSED DETAIL]**. Any unresolved behaviour is listed under Open Decisions rather than treated as a confirmed requirement.

---

## 1. Module Overview

M14 provides handheld ordering and table-service workflows for waiters. A waiter uses a phone to view the floor plan, select a table, record the guest count, browse the menu, customise items, build a cart, place an order, optionally track serving status, and request a bill.

The module is an **Edge/LAN client**. It uses the Order, Table and Kitchen endpoints; it does not own order, table, menu or kitchen domain logic.

**Primary objective:** Let waiters manage table service from a handheld device while keeping order and table data consistent with the restaurant Edge server.

### 1.1 Core capabilities

- Handheld floor plan and table selection.
- Guest-count entry.
- Self-assignment of waiter to a table.
- Menu browsing with search and dietary badges.
- Item customisation for diet, spice and special instructions.
- Cart drawer and one-tap Fire KOT action.
- Ready alerts from the kitchen workflow.
- Mark served.
- Request bill and notify Cashier.
- Optional waiter-to-table assignment toggle in settings.
- Waiter-scoped visibility of tables and orders; Manager exception.

### 1.2 System boundary

| Concern | Owning module |
|---|---|
| Table layout, table status and waiter assignment | M5 — Table and Floor Management |
| Menu categories, product availability and published menu | M7 — Menu and Category Management |
| Product master and product attributes | M8 — Product Management |
| Order creation and lifecycle | M11 — Order Management |
| Kitchen ticket generation and ticket item lifecycle | M13 — Kitchen Order Ticket |
| Kitchen live board and Ready status | M12 — Kitchen Display System |
| Notifications | Notification module |
| Offline synchronisation | M28 — Offline Data Synchronisation |

M14 orchestrates the waiter-facing experience and calls the owning module APIs. It must not directly update another module's database tables.

---

## 2. Actors, Access and Data Visibility

### 2.1 Actors

| Actor | M14 usage |
|---|---|
| Waiter | Primary user; operates assigned tables and own orders |
| Manager | May access waiter tables/orders beyond own assignment, subject to role/permission configuration |
| Edge server | Validates requests, applies assignment and access rules, persists operational changes |
| Kitchen / KDS | Produces item-ready updates consumed by the waiter experience |
| Cashier | Receives bill-request notification and continues billing workflow |

### 2.2 Source-defined access rule

- A waiter sees **their own tables and orders only**.
- Manager is the stated exception.
- Own-data restriction must be enforced server-side; hiding records in the UI is not sufficient.

**[PROPOSED DETAIL]** Every request should derive the acting waiter identity from the authenticated session/JWT. Do not trust a client-supplied `waiterId` as proof of ownership. Validate branch scope and active staff status before returning or mutating a table/order.

### 2.3 Permission notes

The M14 source does not define dedicated permission-code strings for every waiter action. The relevant Order, Table, Kitchen and Notification APIs must enforce their owning module's permission rules.

**[PROPOSED DETAIL]** Reuse the central RBAC configuration and avoid inventing M14-specific permission codes until the permission matrix confirms them.

---

## 3. User Workflow

### 3.1 End-to-end service flow

1. Waiter logs in.
2. Waiter opens the floor plan on the phone.
3. Waiter selects a table.
4. Waiter enters the guest count.
5. Waiter assigns self to the table (or uses the configured waiter-to-table assignment behaviour).
6. Waiter opens the menu browser.
7. Waiter searches/browses products and checks dietary badges.
8. Waiter customises items using diet, spice and instructions.
9. Waiter adds items to the cart drawer and reviews the cart.
10. Waiter taps **Fire KOT**.
11. The waiter client sends the order action through the Order/Kitchen API contract.
12. Kitchen processes the ticket; the waiter receives Ready alerts.
13. Kitchen status tracking and marking items served are optional; a confirmed order can proceed to bill request.
14. When guests ask to pay, waiter selects **Request Bill**.
15. The Edge server sets the table status to `BILL_REQUESTED` and notifies Cashier.

### 3.2 Table selection and waiter self-assignment

- Floor plan is displayed on the phone.
- Waiter taps a table and enters guest count.
- Waiter can assign self to the selected table.
- Table ownership and status are controlled by M5.
- The waiter must not be able to take over another waiter's table unless Manager access or an authorised reassignment applies.

**[PROPOSED DETAIL]** Treat table claim/assignment as a server-side operation. If two waiters attempt to claim the same table concurrently, the Edge server should accept only a valid assignment and return a conflict for the losing request; refresh the floor plan before retrying. Confirm the exact conflict response with M5's API contract.

### 3.3 Menu browsing and item customisation

The menu browser supports:
- Search.
- Dietary badges.
- Item customisation for diet.
- Spice preference.
- Special instructions.

The master source does not define the exact option catalogue, validation limits, or whether each customisation changes price.

**[PROPOSED DETAIL]**
- Load only the published menu and products available to the active branch.
- Preserve the selected product ID and product snapshot expected by M11.
- Store customisation selections and free-text instructions as order-item modifiers/notes through the Order API contract.
- Escape and validate user-entered instructions before display or persistence.
- Do not allow a waiter client to override product price through customisation payloads.

### 3.4 Cart and Fire KOT

- Cart is presented as a drawer.
- Waiter can review selected items before firing.
- **Fire KOT** is the one-tap action identified by the source.

M14 uses Order and Kitchen endpoints. Order confirmation and KOT creation remain owned by M11/M13; M14 must not generate KOT records itself.

**[PROPOSED DETAIL]** Disable repeated taps while a submission is in progress and use the idempotency contract exposed by M11 where applicable. After an ambiguous network timeout, reload the order state before attempting another fire action.

### 3.5 Ready alert and serving

- Waiter receives Ready alerts.
- Waiter marks served.
- Kitchen item status is owned by the kitchen workflow; the waiter UI should invoke the supported status action rather than directly changing KOT data.

**[PROPOSED DETAIL]** Display the table, order and item context in the alert, and deep-link to the relevant order. Only show items belonging to the waiter's permitted order scope.

### 3.6 Request bill

The source-defined behaviour is:

- Waiter requests the bill.
- Table status becomes `BILL_REQUESTED`.
- Cashier is notified.

M14 does not calculate the invoice, collect payment or mark the order paid. Those actions belong to the billing/payment modules.

---

## 4. Screens and Client Events

### 4.1 Screens

| Screen | Responsibility |
|---|---|
| Waiter floor plan | Show the floor plan and tables available to the waiter |
| Menu browser | Search and browse menu items with dietary badges |
| Cart drawer | Review selected items and customisations before Fire KOT |
| My orders | Show the waiter's own orders |
| Alerts | Show kitchen Ready alerts and bill-related feedback |

### 4.2 Source-defined events

| Event | Meaning |
|---|---|
| `TableSelected` | Waiter selects a table |
| `ItemCustomized` | Waiter changes item diet, spice or instructions |
| `FireKotRequested` | Waiter requests the KOT/order fire action |
| `ServedMarked` | Waiter marks an item/order as served |
| `BillRequested` | Waiter requests the bill |

### 4.3 [PROPOSED DETAIL] UI states

The source names events but does not define M14 BLoC states. The following state names are implementation suggestions, not source requirements:

- `WaiterLoading`
- `WaiterFloorPlanLoaded`
- `TableSelectionInProgress`
- `TableSelectedState`
- `MenuLoading`
- `MenuLoaded`
- `CartUpdated`
- `FireKotInProgress`
- `FireKotFailed`
- `MyOrdersLoaded`
- `ReadyAlertsLoaded`
- `BillRequestInProgress`
- `WaiterError`

Use the existing Flutter/Dart/BLoC architecture. Screens should dispatch events to BLoCs; BLoCs should call repository interfaces; repositories should call Edge APIs. A waiter client must not write directly to the Edge SQLite database.

### 4.4 UI behaviour

**[PROPOSED DETAIL]**
- Use large, touch-friendly controls suitable for handheld operation.
- Keep table identity visible while browsing menu/cart.
- Preserve a draft cart during temporary navigation within the active table session.
- Show clear loading, empty, error and retry states.
- On a permission error, do not reveal whether another waiter's table/order exists.
- Refresh live table/order/ready state from Edge updates and re-fetch authoritative state after reconnect.

---

## 5. API Contract

The following routes are explicitly listed in the M14 source.

| Method | Endpoint | Purpose |
|---|---|---|
| `PUT` | `/api/v1/tables/{id}/waiter` | Assign waiter to a table |
| `GET` | `/api/v1/orders?waiterId=me` | Retrieve orders scoped to the current waiter |
| `POST` | `/api/v1/orders/{id}/request-bill` | Request bill for an order |

### 5.1 `PUT /api/v1/tables/{id}/waiter`

**Source-defined purpose:** Set/assign the waiter for a table.

**[PROPOSED DETAIL]** The server should:
1. Authenticate the staff member.
2. Resolve tenant and branch from the authenticated context.
3. Validate that the table belongs to the active branch.
4. Validate that the actor may assign the table to the requested waiter (including self-assignment where permitted).
5. Persist the assignment through the Table module.
6. Return the updated assignment/table representation.

The exact request DTO, response DTO and reassignment rules are not specified in the M14 source and must follow M5's API contract.

### 5.2 `GET /api/v1/orders?waiterId=me`

**Source-defined purpose:** Retrieve orders for the current waiter.

- `waiterId=me` is the documented filter.
- Waiter sees own orders only unless Manager exception applies.
- Server-side own-data restriction is mandatory.

**[PROPOSED DETAIL]** Interpret `me` on the server from the authenticated principal; do not treat it as an arbitrary user ID. Support pagination and stable ordering only after aligning with the shared Order API contract.

### 5.3 `POST /api/v1/orders/{id}/request-bill`

**Source-defined purpose:** Request a bill for the selected order.

**Source-defined side effects:**
- Set table status to `BILL_REQUESTED`.
- Notify Cashier.

**[PROPOSED DETAIL]** Validate that:
- The order exists and belongs to the current tenant/branch.
- The waiter is allowed to access the order/table.
- The order is confirmed; kitchen item statuses are not required.
- A duplicate request does not create duplicate notifications or inconsistent table transitions.

The source does not define the request body, response body, duplicate-request semantics or exact notification event name.

### 5.4 API ownership

M14 uses Order, Table and Kitchen endpoints with waiter-scoped filters. Do not add M14-owned duplicate endpoints for order creation, KOT generation, kitchen status transitions or invoice generation without an approved API design.

---

## 6. Data Model

### 6.1 Source-defined table

The M14 source defines:

```sql
waiter_assignments (
    id,
    branch_id,
    table_id,
    waiter_id,
    assigned_at,
    released_at
)
```

This records waiter-to-table assignment history.

### 6.2 Field interpretation

| Field | Meaning |
|---|---|
| `id` | Assignment record identifier |
| `branch_id` | Branch in which the assignment applies |
| `table_id` | Assigned table |
| `waiter_id` | Waiter assigned to the table |
| `assigned_at` | Assignment start time |
| `released_at` | Assignment end time; nullability is not explicitly specified |

### 6.3 [PROPOSED DETAIL] Data integrity

- Keep assignment records scoped to the branch.
- Preserve prior assignment records instead of overwriting history.
- Validate table and waiter belong to the same branch.
- Use server-generated timestamps.
- Prevent overlapping active assignments for a table unless an approved workflow explicitly permits them.
- Index the columns used for current assignment lookup, such as `branch_id`, `table_id`, `waiter_id` and active/released status.

The master source does not provide a complete SQL DDL, primary-key type, foreign-key definitions, indexes, uniqueness constraints or soft-delete fields for `waiter_assignments`. Confirm these against the shared database migration before implementation.

---

## 7. Integrations and Event Flow

### 7.1 Dependencies

M14 depends on:
- Orders.
- Tables.
- Menu.
- Notification.

The handheld workflow also consumes Kitchen readiness information through the relevant Kitchen/KDS contract.

### 7.2 Integration responsibilities

| Integration | M14 responsibility | Owning system responsibility |
|---|---|---|
| Table | Select table; assign self; request table service | M5 validates and persists table state/assignment |
| Menu | Browse/search products and dietary badges | M7/M8 serve published menu and product data |
| Order | Fire order/KOT action; list own orders; request bill | M11 owns order creation and lifecycle |
| Kitchen | Receive Ready updates; mark served through supported contract | M12/M13 own kitchen ticket and item workflow |
| Notification | Display Ready and bill-related alerts | Notification workflow routes alerts to recipients |

### 7.3 Request-bill sequence

1. Waiter selects **Request Bill** for an eligible order.
2. M14 calls `POST /api/v1/orders/{id}/request-bill`.
3. Server validates the authenticated waiter and order/table access.
4. Order/Table integration updates the table to `BILL_REQUESTED`.
5. Cashier is notified.
6. M14 displays the request result.

The source does not define the notification transport or event name for this operation.

### 7.4 [PROPOSED DETAIL] Live updates

- Use the existing Edge real-time update mechanism where available.
- Re-fetch current table/order state after reconnect.
- Treat server state as authoritative; do not assume a local optimistic update succeeded.
- Deduplicate repeated alerts using the event/message identifier if the Notification contract provides one.

---

## 8. Offline and Network Behaviour

The source-defined offline statement is:

> M14 works while the Edge is reachable on the LAN.

This means the waiter app can continue operating without internet when it can still communicate with the restaurant Edge server. It does **not** establish that the waiter app can complete operations when disconnected from the Edge/LAN.

### 8.1 Source-defined network test cases

- Weak Wi-Fi.
- App killed mid-order.
- Waiter attempts to access another waiter's table.

### 8.2 [PROPOSED DETAIL] Recovery behaviour

- If an API request times out, show a retry/reload action and do not assume the operation failed or succeeded.
- On app restart, reload the floor plan and waiter-scoped orders from Edge.
- Before resubmitting a Fire KOT action after a timeout, query the order state and use the Order module's idempotency behaviour.
- If Edge is unreachable, show a clear connection state and prevent actions that require server confirmation.
- Do not create an independent local order that could conflict with Edge state unless a separately approved offline-client queue is introduced.

M28 owns cross-system offline synchronisation. M14 does not implement its own Cloud sync engine.

---

## 9. Security and Validation

### 9.1 Mandatory source rule

- Waiter own-data restriction is enforced server-side.
- A waiter attempting to access another waiter's table/order is a required negative test; expected response is `403` or `404` as specified by the source.

### 9.2 [PROPOSED DETAIL] Security checklist

- Authenticate every staff API request.
- Resolve `me` from the authenticated principal.
- Enforce tenant and branch scope.
- Enforce waiter assignment before returning or mutating table/order data.
- Apply Manager exception only through the central role/permission policy.
- Validate customisation fields and special-instruction text.
- Do not expose customer information or financial fields beyond the waiter workflow's approved need.
- Record assignment and sensitive state changes using the owning module's audit facilities.
- Never rely on client-side route guards as the sole access control.

---

## 10. Error Handling

The M14 source does not define a complete error-code catalogue.

**[PROPOSED DETAIL]** Map owning API errors into actionable waiter UI messages:

| Condition | UI behaviour |
|---|---|
| `401` unauthenticated/session expired | Ask waiter to sign in again |
| `403` forbidden | Show access denied without exposing another user's data |
| `404` table/order not found or intentionally concealed | Refresh floor plan/order list |
| `409` assignment/state conflict | Reload current table/order state and retry only if still valid |
| Network timeout | Show uncertain result; fetch authoritative state before resubmission |
| Edge unreachable | Show LAN connection warning and disable server-dependent actions |
| Validation failure | Highlight the invalid field/item and preserve safe cart state |

These mappings are implementation guidance; confirm exact status codes with the owning API specifications.

---

## 11. Testing Strategy

### 11.1 Source-required tests

1. **Cross-waiter access:** Waiter tries another waiter's table/order; verify `403` or `404`.
2. **Weak Wi-Fi:** Verify the app handles slow or intermittent LAN connectivity.
3. **App killed mid-order:** Verify the waiter can recover the current state after restarting.
4. **Lowest supported phone:** Test the handheld layout and performance on the lowest supported device.

### 11.2 [PROPOSED DETAIL] Functional tests

- Waiter login and floor-plan loading.
- Table selection and guest-count entry.
- Self-assignment to a table.
- Assignment toggle behaviour when enabled/disabled in settings.
- Menu search and dietary badge rendering.
- Item customisation for diet, spice and instructions.
- Cart add, update, remove and review.
- Fire KOT success and failure.
- Repeated Fire KOT tap / timeout recovery.
- Ready alert display and navigation to the correct order.
- Mark served action through the owning Kitchen/Order API.
- Request bill updates table to `BILL_REQUESTED`.
- Cashier receives bill-request notification.
- Waiter cannot list or retrieve another waiter's orders.
- Manager exception follows central RBAC policy.
- App restart restores server-authoritative state.

### 11.3 Integration tests

- M14 ↔ M5 waiter assignment.
- M14 ↔ M7/M8 menu retrieval.
- M14 ↔ M11 order list and Fire KOT flow.
- M14 ↔ M12/M13 Ready and served workflow.
- M14 ↔ Notification for Ready and Cashier alerts.

---

## 12. Implementation Guidance

### 12.1 Suggested Flutter structure

**[PROPOSED DETAIL]** Align paths with the existing project folder conventions before adding files.

```text
lib/
  features/
    waiter/
      data/
        datasources/
          waiter_remote_data_source.dart
        repositories/
          waiter_repository_impl.dart
      domain/
        entities/
          waiter_assignment.dart
          waiter_table.dart
        repositories/
          waiter_repository.dart
        usecases/
          load_waiter_floor_plan.dart
          assign_table_to_self.dart
          load_my_orders.dart
          fire_kot.dart
          mark_served.dart
          request_bill.dart
      presentation/
        bloc/
          waiter_bloc.dart
          waiter_event.dart
          waiter_state.dart
        screens/
          waiter_floor_plan_screen.dart
          menu_browser_screen.dart
          my_orders_screen.dart
          waiter_alerts_screen.dart
        widgets/
          table_card.dart
          menu_item_tile.dart
          cart_drawer.dart
          ready_alert_tile.dart
```

This is a proposed feature-first organisation, not a source-defined folder layout. Keep the project's established BLoC, repository and GoRouter patterns.

### 12.2 Suggested delivery order

1. Confirm M5 table assignment API and settings toggle behaviour.
2. Implement waiter authentication context and server-side own-data filtering.
3. Build floor plan and table selection.
4. Implement guest count and self-assignment.
5. Integrate menu browser and item customisation.
6. Implement cart and Fire KOT through M11/M13.
7. Integrate Ready alerts and mark-served action.
8. Implement request-bill endpoint integration and Cashier notification.
9. Complete weak-Wi-Fi, restart and lowest-device tests.

The order above is a **[PROPOSED DETAIL]** implementation sequence.

---

## 13. Acceptance Criteria

M14 is ready for acceptance when:

- Waiter can log in and view the handheld floor plan.
- Waiter can select a table, enter guest count and assign self.
- Menu browser supports search and dietary badges.
- Waiter can customise diet, spice and instructions.
- Cart drawer supports review before Fire KOT.
- Fire KOT uses the owning Order/KOT workflow.
- Waiter receives Ready alerts and can mark served using the supported API.
- Waiter can request a bill.
- Bill request sets table status to `BILL_REQUESTED` and notifies Cashier.
- Waiter can see only their own tables and orders unless Manager access applies.
- The app remains usable while the Edge is reachable on LAN without internet.
- Cross-waiter access, weak Wi-Fi, app restart and lowest-supported-phone tests pass.

---

## 14. Open Decisions and Source Gaps

The following details are not fully defined by the M14 source and must not be assumed silently:

1. Exact guest-count minimum/maximum and whether it can be edited after seating.
2. Exact behaviour when waiter-to-table assignment toggle is disabled.
3. Whether Manager can reassign an active table and which permission controls it.
4. Exact diet and spice option catalogues and whether customisations affect price.
5. Maximum length and allowed characters for special instructions.
6. Whether cart state must survive app termination and for how long.
7. Exact API request/response DTOs for table assignment, Fire KOT and request bill.
8. Exact Ready notification event name, transport and acknowledgement behaviour.
9. Exact API used by waiter to mark served and whether serving is per item or per order.
10. Waiters may request a bill for their own confirmed table orders; item kitchen statuses are not required.
11. Whether repeated bill requests are idempotent and how duplicate Cashier notifications are prevented.
12. Exact waiter-assignment database constraints, foreign keys and indexes.
13. Whether a waiter can serve multiple tables concurrently and whether one table can have multiple active waiters.
14. Exact behaviour if the Edge becomes unreachable mid-action; source guarantees operation only while Edge remains reachable on LAN.

---

## 15. Deliverables

The master documentation identifies these M14 deliverables:

- Flutter mobile layouts.
- Request-bill endpoint.

**[PROPOSED DETAIL]** The implementation package should also include BLoC/repository integration, waiter assignment integration, API tests, cross-waiter access tests and handheld-device validation.
