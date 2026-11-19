# M7 — Menu and Category Management (CORE)

**Project:** DineMaster  
**Module:** M7 — Menu and Category Management  
**Document type:** Module-level technical specification  
**Source:** DineMaster Master Technical Documentation, Module M7 and Database Architecture §6.5  
**Status:** CORE module

> **Source discipline:** This document preserves the requirements and interfaces stated in the master documentation. Details explicitly marked **[PROPOSED DETAIL]** are implementation guidance added to make the module actionable; they are not confirmed business requirements.

---

## 1. Module Overview

Menu and Category Management organises what a restaurant sells. It provides the category structure and branch-level product availability consumed by POS, Waiter, and QR ordering clients.

**Primary users:** Admin, Manager.

The module supports:
- Creating and maintaining menu categories.
- Defining category display order and active/inactive state.
- Managing product availability by branch.
- Publishing menu versions to Edge devices.
- Serving the menu from Edge local cache when Cloud connectivity is unavailable.
- Preserving historical order data when menu details change.

### 1.1 Core business rules

1. Categories have an order and an active flag.
2. Product availability is branch-specific.
3. Menu scheduling is a **Target** capability, not a confirmed current implementation.
4. A menu version is published to Edge devices.
5. Edges receive published changes through sync pull within minutes.
6. A category containing active products cannot be deleted; it must be archived.
7. Menu changes apply to **new orders only**. Existing orders retain their snapshots.
8. Menu management requires the `menu:manage` permission.
9. Price changes are audited.

---

## 2. Scope

### 2.1 In scope

- Category list and category form.
- Drag-and-drop category ordering.
- Branch availability matrix.
- Category create, update, reorder, archive/delete handling.
- Branch-specific product availability and price override.
- Full menu-tree retrieval for clients.
- Menu publication/version delivery to Edge devices.
- ETag-based menu caching and cache invalidation.
- Offline menu reads through Edge local cache.
- Audit of price changes.

### 2.2 Out of scope / separate modules

- Product master fields and product image management — **M8 Product Management**.
- Recipe and BOM configuration — **M9 Recipe and BOM Management**.
- POS cart, order creation, and billing — POS / Order modules.
- QR guest ordering workflow — **M15 Customer Self-Ordering / QR Ordering**.
- Offline push/pull conflict processing — **M28 Offline Data Synchronisation**.

M7 provides menu data to these consumers; it does not own their complete workflows.

---

## 3. Actors and Permissions

| Actor | Responsibility |
|---|---|
| Admin | Create and manage categories, configure menu availability, publish menu changes |
| Manager | Manage menu categories and branch menu configuration, subject to assigned permissions |
| POS | Read the published branch menu |
| Waiter | Read the published branch menu |
| QR ordering client | Read the published branch menu |
| Edge device | Cache and serve menu data locally |

**Authorization rule:** `menu:manage` is the required permission for menu management operations.

**[PROPOSED DETAIL]** Read-only menu endpoints should be available only to authenticated staff/device clients or to the explicitly authorized guest QR flow. The exact authentication policy for each consumer should follow the corresponding module's security contract.

---

## 4. User Workflow

### 4.1 Create and publish a category/menu

1. Admin opens Menu Management.
2. The application loads the category list.
3. Admin creates a category and saves it.
4. Admin adds products to the menu using M8 Product Management.
5. Admin configures product availability for the required branches.
6. Admin publishes the menu.
7. A new menu version is made available to Edge devices.
8. Edge devices receive the changes through sync pull within minutes.
9. POS, Waiter, and QR clients read the branch menu from the Edge cache or the available menu endpoint.

### 4.2 Reorder categories

1. Admin opens the category list.
2. Admin drags a category to a new position.
3. The client submits the new ordering.
4. The backend persists the category order.
5. `CategoryReordered` is emitted.
6. The menu cache/version is refreshed so clients receive the new order.

Steps 4–6 are implementation flow guidance; the source confirms drag sorting, the reorder endpoint, and the `CategoryReordered` event.

### 4.3 Archive a category

1. Admin requests removal of a category.
2. The service checks whether the category has active products.
3. If active products exist, hard deletion is rejected.
4. The category is archived/inactivated instead.
5. The updated menu is published to consumers.

The exact archive mechanism should preserve historical order references and snapshots.

---

## 5. Screens and UI Requirements

### 5.1 Category List

Required source-defined capabilities:
- Display categories.
- Show active/inactive state.
- Support drag sorting.
- Load category data through the category feature.

Expected event:
- `CategoriesLoaded`

**[PROPOSED DETAIL]** Suggested list columns:
- Category name
- Active status
- Display order
- Product count
- Last updated

Suggested actions:
- Add category
- Edit category
- Reorder
- Archive

### 5.2 Category Form

Required purpose: create or edit a category.

**[PROPOSED DETAIL]** Suggested fields:
- Category name
- Active flag

The master schema defines `name`, `sort_order`, and `is_active`. Do not add fields such as description, icon, image, or colour unless separately approved.

Expected event:
- `CategorySaved`

### 5.3 Branch Availability Matrix

Required source-defined purpose: configure product availability by branch.

The matrix is backed by:
- `branch_id`
- `product_id`
- `is_available`
- `price_override`

**[PROPOSED DETAIL]** A practical matrix can display products as rows and branches as columns, with availability toggles and optional branch price overrides. The source does not prescribe the exact visual layout.

---

## 6. Backend Design

### 6.1 Components

| Component | Responsibility |
|---|---|
| `CategoryController` | Handle category HTTP requests and responses |
| `CategoryService` | Apply category rules, ordering, archive handling, and menu-related business logic |
| `core.categories` | Persist category master data |
| `branch_products` | Persist branch-specific product availability and price overrides |
| Menu endpoint | Return the full menu tree for a branch |
| Edge menu cache | Serve cached menu data when Cloud is unavailable |

### 6.2 Suggested service responsibilities

The source names `CategoryController` and `CategoryService`; the following breakdown is **[PROPOSED DETAIL]**.

`CategoryService`:
- Create category.
- Update category.
- Validate category deletion/archive rules.
- Persist category ordering.
- Read categories for a tenant.
- Read branch product availability.
- Coordinate menu version publication/cache invalidation.

Keep controller logic thin. Business rules belong in the service layer.

### 6.3 Transaction boundaries

**[PROPOSED DETAIL]**
- Category create/update should commit category data and its version/update metadata atomically.
- Reordering should update all affected category sort positions in one database transaction.
- Branch availability changes should commit before a new menu version is advertised.
- If publication fails after a database commit, retain the committed menu data and retry publication; do not roll back unrelated committed changes.

---

## 7. Database Design

### 7.1 `core.categories`

The master documentation's §6.5 defines the category table as follows:

```sql
CREATE TABLE core.categories (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  name varchar(100) NOT NULL,
  sort_order smallint NOT NULL DEFAULT 0,
  is_active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version integer NOT NULL DEFAULT 1,
  deleted_at timestamptz,
  UNIQUE (tenant_id, name)
);
```

| Column | Type | Purpose |
|---|---|---|
| `id` | UUID | Category primary key |
| `tenant_id` | UUID | Tenant ownership |
| `name` | varchar(100) | Category name |
| `sort_order` | smallint | Category display order |
| `is_active` | boolean | Active/inactive state |
| `created_at` | timestamptz | Creation timestamp |
| `updated_at` | timestamptz | Last update timestamp |
| `version` | integer | Record version |
| `deleted_at` | timestamptz, nullable | Soft-delete marker |

The database enforces unique category names within a tenant. Branch-level product availability is stored separately.

### 7.2 `branch_products`

The M7 source specifies this structure:

```sql
branch_products(
  branch_id,
  product_id,
  is_available,
  price_override
)
```

The source does not provide a complete DDL definition for this table.

**[PROPOSED DETAIL]** A likely relational implementation would use a composite uniqueness constraint on `(branch_id, product_id)` and foreign keys to the branch and product records. Confirm the exact schema against the approved database migration before implementation.

| Field | Purpose |
|---|---|
| `branch_id` | Branch to which the configuration applies |
| `product_id` | Product being configured |
| `is_available` | Whether the product is available at this branch |
| `price_override` | Optional branch-specific price override |

### 7.3 Product relationship

Products reference categories through `core.products.category_id`. Category archival must therefore account for products that remain associated with the category.

The product table and its detailed fields are owned by M8.

### 7.4 Historical order snapshots

Menu changes must not rewrite old orders. When an order is created, its item details and price must be snapshotted by the ordering module.

M7 must not update historical order snapshots when:
- Category name/order changes.
- Product price changes.
- Branch availability changes.
- A category is archived.

---

## 8. API Contract

The following endpoints are specified by the master documentation.

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/v1/categories` | Retrieve categories |
| `POST` | `/api/v1/categories` | Create category |
| `PUT` | `/api/v1/categories/{id}` | Update category |
| `DELETE` | `/api/v1/categories/{id}` | Delete/archive category subject to business rules |
| `PUT` | `/api/v1/categories/reorder` | Update category order |
| `GET` | `/api/v1/menu?branchId=` | Retrieve full menu tree for a branch |

### 8.1 `GET /api/v1/categories`

**Purpose:** Load categories for menu administration.

**[PROPOSED DETAIL]** Support tenant-scoped results and, if needed by the UI, an active-state filter. Do not expose categories belonging to another tenant.

### 8.2 `POST /api/v1/categories`

**Purpose:** Create a category.

**[PROPOSED DETAIL]** Request example:

```json
{
  "name": "Starters",
  "sortOrder": 1,
  "isActive": true
}
```

The field names above are an illustrative API payload, not a source-prescribed JSON schema. The database fields are `name`, `sort_order`, and `is_active`.

### 8.3 `PUT /api/v1/categories/{id}`

**Purpose:** Update category information.

**[PROPOSED DETAIL]** Validate the category belongs to the authenticated tenant before updating it. If the name changes, enforce the tenant-level uniqueness rule.

### 8.4 `DELETE /api/v1/categories/{id}`

**Purpose:** Remove a category where allowed.

Business rule:
- If the category has active products, reject deletion and require archival.
- The schema includes `is_active` and nullable `deleted_at`; the source does not fully specify whether the endpoint performs a soft delete, an archive, or both in every case.

**Pending implementation decision:** Define the exact response and persistence behaviour for categories with no active products versus categories with active products.

### 8.5 `PUT /api/v1/categories/reorder`

**Purpose:** Persist drag-and-drop category ordering.

**[PROPOSED DETAIL]** Request example:

```json
{
  "items": [
    { "id": "category-uuid-1", "sortOrder": 1 },
    { "id": "category-uuid-2", "sortOrder": 2 }
  ]
}
```

Use actual UUIDs in implementation. The master source specifies the endpoint but not its request body.

### 8.6 `GET /api/v1/menu?branchId=`

**Purpose:** Return the full menu tree for a branch.

Source-defined behaviour:
- Return categories with active, available products.
- Include the current tax rate.
- Support `If-None-Match`.
- The menu is consumed by POS, Waiter, and QR clients.
- Edge serves it from local cache when Cloud is down.

**[PROPOSED DETAIL]** Example response shape:

```json
{
  "branchId": "branch-uuid",
  "menuVersion": 12,
  "categories": [
    {
      "id": "category-uuid",
      "name": "Starters",
      "sortOrder": 1,
      "products": [
        {
          "id": "product-uuid",
          "name": "Paneer Tikka",
          "price": 220.00,
          "taxRate": 5.00
        }
      ]
    }
  ]
}
```

This is an illustrative contract only. The source does not define the exact JSON response fields or the menu-version field name.

---

## 9. Menu Publication, Versioning and Edge Cache

### 9.1 Publication flow

1. Admin changes category/menu configuration.
2. Backend persists the change.
3. A menu version is advanced/published.
4. Edge devices receive the change through sync pull within minutes.
5. Edge updates its local menu cache.
6. POS, Waiter, and QR clients read the current branch menu.

### 9.2 ETag behaviour

The menu endpoint supports conditional requests using `If-None-Match`.

Expected HTTP behaviour:
- If the client's ETag matches the current representation, return `304 Not Modified`.
- If the menu has changed, return the updated menu representation and a new ETag.

The source confirms ETag support; the exact ETag generation algorithm is not specified.

### 9.3 Offline behaviour

When Cloud is unavailable:
- Edge serves the menu from its local cache.
- POS, Waiter, and QR clients continue reading the cached menu through Edge.

M7's offline responsibility is menu availability/read access. Synchronisation retry, cursor handling, and conflict resolution belong to M28.

### 9.4 Cache invalidation

Cache invalidation must be tested. Any menu-affecting change must eventually cause Edge/client caches to refresh.

**[PROPOSED DETAIL]** Treat the menu as a versioned branch-level representation. Invalidate or refresh the cached representation after category changes, category reordering, branch availability changes, and relevant price changes. Ensure a failed cache refresh does not erase the last known-good menu.

---

## 10. Validation and Business Rules

| Scenario | Expected behaviour |
|---|---|
| Duplicate category name in same tenant | Reject using tenant-level uniqueness |
| Category name exceeds 100 characters | Reject according to database limit |
| Category has active products and delete is requested | Do not hard-delete; archive instead |
| Category is reordered | Persist new `sort_order` values |
| Product unavailable at a branch | Exclude it from that branch's active menu response |
| Product available at another branch | Do not assume availability is shared across branches |
| Branch has a price override | Use branch-specific configuration in menu output |
| Menu changes after an order was placed | Existing order snapshot remains unchanged |
| Edge is offline from Cloud | Serve last available menu from local cache |
| Client sends matching ETag | Return not-modified response |
| Client sends stale ETag | Return current menu representation |

**[PROPOSED DETAIL]** Validate that a supplied `branchId` belongs to the authenticated tenant. Validate that category IDs submitted for reorder also belong to that tenant, and reject duplicate/missing IDs in a reorder request.

---

## 11. Security and Audit

Source-defined controls:
- `menu:manage` permission is required for management operations.
- Price changes are audited.
- Menu is tenant-scoped through `tenant_id` in the category schema.

**[PROPOSED DETAIL]**
- Derive `tenant_id` from the authenticated principal, not from an untrusted request body.
- Verify branch ownership before reading or changing `branch_products`.
- Record actor, timestamp, branch, old value, and new value for price changes.
- Do not allow a client to bypass category archive rules by calling the delete endpoint directly.
- Apply the same authorization checks to reorder and branch availability endpoints as to category create/update.

---

## 12. Events

The master documentation defines these category events:

| Event | Meaning |
|---|---|
| `CategoriesLoaded` | Category list has been loaded |
| `CategorySaved` | Category has been saved |
| `CategoryReordered` | Category order has changed |

These names should be retained in the UI/state-management implementation.

**[PROPOSED DETAIL]** If the project uses a shared event envelope, include tenant/branch context and an event timestamp according to the existing application convention. Do not introduce additional public event names without aligning them with the architecture.

---

## 13. Testing Strategy

The master documentation explicitly calls out:
- Reorder.
- Archive with products.
- Menu fetch while offline.
- Cache invalidation.

Recommended test coverage:

### 13.1 Unit tests

- Category create validation.
- Category update validation.
- Duplicate category name handling.
- Active-product check before deletion.
- Archive behaviour.
- Sort-order update logic.
- Branch-specific availability selection.
- Branch price override selection.

### 13.2 API integration tests

- `GET /api/v1/categories`
- `POST /api/v1/categories`
- `PUT /api/v1/categories/{id}`
- `DELETE /api/v1/categories/{id}`
- `PUT /api/v1/categories/reorder`
- `GET /api/v1/menu?branchId=`

### 13.3 Menu and cache tests

1. Active category and available products appear in the menu.
2. Inactive category is not presented as an active menu category.
3. Unavailable branch product is excluded.
4. Branch-specific price override is reflected.
5. Current tax rate is returned.
6. Matching `If-None-Match` returns `304`.
7. Changed menu produces a refreshed representation/ETag.
8. Edge serves the cached menu when Cloud is unavailable.
9. Cache invalidation occurs after menu changes.
10. Existing order snapshots remain unchanged after menu edits.

### 13.4 Authorization tests

- User without `menu:manage` cannot manage categories.
- User with permission can manage categories within the authorized tenant.
- Cross-tenant category and branch identifiers are rejected.
- Price changes create an audit record.

---

## 14. Dependencies and Consumers

### Dependencies

- Tenant and branch context from the core restaurant/branch setup.
- M8 Product Management for product records.
- Tax configuration (`core.tax_slabs`) for current tax rates.
- M28 Offline Data Synchronisation for Edge sync delivery.

### Consumers

- POS
- Waiter
- QR ordering
- Edge local menu cache

### Related modules

| Module | Relationship |
|---|---|
| M8 Product Management | Products belong to categories |
| M9 Recipe and BOM Management | Product recipe is managed separately |
| M10 POS / Order workflow | Reads menu and snapshots order item details |
| M15 QR Ordering | Reads branch menu |
| M28 Offline Data Synchronisation | Delivers changes to Edge devices |

---

## 15. Implementation Deliverables

The source identifies these delivery areas:
- Database.
- Backend.
- Flutter menu administration.
- Edge cache.

Suggested completion checklist:

- [ ] `core.categories` migration is applied.
- [ ] Branch product availability storage is confirmed and migrated.
- [ ] `CategoryController` is implemented.
- [ ] `CategoryService` is implemented.
- [ ] Category list screen is implemented.
- [ ] Category form is implemented.
- [ ] Drag sorting is implemented.
- [ ] Branch availability matrix is implemented.
- [ ] Category archive/delete rule is enforced.
- [ ] Menu tree endpoint returns active categories and available products.
- [ ] Current tax rate is included in menu output.
- [ ] ETag handling is implemented.
- [ ] Menu version publication to Edge is connected to sync pull.
- [ ] Edge local menu cache works without Cloud connectivity.
- [ ] Price changes are audited.
- [ ] Historical order snapshots are not modified by menu updates.
- [ ] Required tests pass.

---

## 16. Definition of Done

M7 is complete when:

1. Admin and Manager users with `menu:manage` can create and manage categories.
2. Categories can be reordered through drag sorting and the order persists.
3. A category with active products cannot be hard-deleted and is archived instead.
4. Branch-level product availability and price overrides are stored and respected.
5. The branch menu endpoint returns active categories, available products, and the current tax rate.
6. Menu responses support ETag caching.
7. Published menu changes reach Edge devices through sync pull within minutes.
8. Edge can serve the last cached menu while Cloud is unavailable.
9. POS, Waiter, and QR clients can consume the branch menu.
10. Menu changes do not alter existing order snapshots.
11. Price changes are audited.
12. Reorder, archive, offline menu fetch, and cache invalidation tests pass.

---

## 17. Open Decisions / Items to Confirm

The master documentation does not fully define the following. Confirm before treating them as fixed requirements:

1. **Menu schedule:** Scheduling is marked Target. Define whether it is included in the current release.
2. **Publication action:** Define whether every save auto-publishes or an explicit Publish action is required.
3. **Menu version model:** Define where the version is stored and whether it is tenant-wide or branch-specific.
4. **Branch price override:** Define whether `price_override = NULL` means use the product's base price.
5. **Delete/archive semantics:** Define the exact behaviour of `DELETE /api/v1/categories/{id}` for categories with no active products and for categories with active products.
6. **Category name uniqueness:** The schema enforces unique `(tenant_id, name)`. Confirm whether archived category names can be reused; the shown schema does not use a partial unique index.
7. **Reorder payload:** Define request format, ordering validation, and behaviour for omitted categories.
8. **ETag generation:** Define whether ETag is derived from a menu version, content hash, or another stable representation.
9. **Edge freshness:** The source says sync pull occurs within minutes; confirm the operational target and retry policy.
10. **Inactive category handling:** Confirm whether inactive categories are omitted entirely from client menu responses or returned with an inactive flag for administrative consumers.

---

## 18. Source Traceability

| Requirement | Master documentation location |
|---|---|
| M7 purpose and users | Chapter 9, M7.A |
| Category order, active flag, branch availability, menu schedule Target, Edge publication | Chapter 9, M7.B |
| Admin creates category, adds products, publishes; Edge sync pull | Chapter 9, M7.C |
| Category list, drag sort, form, branch availability matrix, events | Chapter 9, M7.D |
| `CategoryController`, `CategoryService` | Chapter 9, M7.E |
| `core.categories`, `branch_products` | Chapter 9, M7.F |
| Category and menu API endpoints | Chapter 9, M7.G |
| Active/available products, current tax rate, ETag | Chapter 9, M7.H |
| POS, Waiter, QR consumers; Edge local cache | Chapter 9, M7.I |
| `menu:manage`, price audit | Chapter 9, M7.J |
| Reorder, archive with products, offline fetch | Chapter 9, M7.K |
| Cache invalidation tests | Chapter 9, M7.L |
| DB, Backend, Flutter admin, Edge cache deliverables | Chapter 9, M7.M |
| Category table definition | Chapter 6.5, Migration script V2 |
