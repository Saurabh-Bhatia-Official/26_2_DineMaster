# M9 — Recipe and BOM Management (INV)

**Project:** DineMaster  
**Module:** M9 — Recipe and BOM Management  
**Document type:** Module-level technical specification  
**Source:** DineMaster Master Technical Documentation, Chapter 9 (M9) and Chapter 6.7 (Migration script V4)  
**Status:** INV module

> **Source discipline:** This document follows the M9 requirements and database definitions in the master documentation. Implementation guidance not explicitly defined by the source is marked **[PROPOSED DETAIL]**. Unspecified behaviour and schema gaps are recorded as open decisions.

---

## 1. Module Overview

Recipe and BOM Management links each menu dish to its raw ingredients so DineMaster can calculate dish cost and deduct ingredient stock automatically.

**Users:** Admin, Manager.  
**Chef:** Reads recipe information.

A recipe describes the ingredients, quantity, unit, yield/portion, cooking steps, preparation time, cooking time, difficulty, and training video for a product.

When an order is paid, the system deducts the corresponding Bill of Materials (BOM) from inventory. The deduction quantity is based on recipe quantity multiplied by sold quantity, converted to the ingredient's base unit. Stock batches are consumed using FEFO (First Expiry, First Out), with the oldest expiry consumed first.

### 1.1 Core responsibilities

- Link a product to raw ingredients.
- Maintain recipe items with ingredient, quantity, and unit.
- Maintain yield and portion.
- Maintain cooking steps.
- Maintain preparation and cooking time.
- Maintain difficulty and training video.
- Support one active recipe version at a time.
- Calculate recipe cost.
- Provide recipe information to Chef.
- Support inventory BOM deduction after paid orders.
- Convert compatible units within the same unit family.
- Preserve the rule that recipe changes affect future sales only.

---

## 2. Scope

### 2.1 In scope

- Recipe editor.
- Recipe item/ingredient mapping.
- Quantity and unit management.
- Yield and portion.
- Cooking steps.
- Prep time and cook time.
- Difficulty.
- Training video link/player (local or URL).
- Recipe version and active-version handling.
- Recipe cost summary.
- Recipe retrieval/update APIs.
- Product-to-recipe relationship.
- Unit conversion.
- BOM deduction calculation.
- FEFO stock batch consumption.
- Cost recalculation job.

### 2.2 Out of scope / related modules

| Concern | Owner |
|---|---|
| Product master and product IDs | M8 Product Management |
| Ingredient master and UOM | Inventory module |
| Stock batches and stock movements | Inventory module |
| Purchase/receiving of ingredients | Inventory / Purchasing |
| Order payment event | Payment / Order modules |
| Order item quantity and product snapshot | Order Management |
| Menu categories | M7 Menu and Category Management |

M9 defines the recipe/BOM relationship and calculation rules. Inventory owns stock levels, batches, and stock movement records.

---

## 3. Actors and Permissions

| Actor | Responsibility |
|---|---|
| Admin | Manage recipes |
| Manager | Create and update recipes |
| Chef | Read recipes and cooking instructions |
| Inventory deduction process | Consume BOM quantities after paid orders |
| Cost recalculation job | Recalculate recipe cost |

Source-defined authorization:
- Recipes are business-sensitive.
- `recipe:view` is required to view recipes.

The source does not specify the exact write permission name.

**[PROPOSED DETAIL]** Use the existing RBAC matrix to define a separate recipe-management permission for create/update operations. Do not assume a permission string such as `recipe:manage` until it is confirmed in the central permission matrix.

---

## 4. Recipe Data Requirements

### 4.1 Recipe header

The source specifies:

| Field | Description |
|---|---|
| Product | Product to which the recipe belongs |
| Version | Recipe version |
| Steps | Cooking steps stored as JSONB |
| Prep time | Preparation time |
| Cook time | Cooking time |
| Portion | Portion/yield information |
| Difficulty | Recipe difficulty |
| Video URL | Training video link |
| Active flag | Whether this recipe version is active |

### 4.2 Recipe item

Each recipe item contains:

| Field | Description |
|---|---|
| Ingredient | Raw ingredient reference |
| Quantity | Required amount; must be greater than zero |
| Unit | Unit in which the recipe quantity is expressed |

The inventory schema links recipe items to:
- `product_id`
- `ingredient_id`
- `quantity`

The source migration does not show a unit column on `inventory.recipe_items`; ingredient UOM is stored on `inventory.ingredients.uom`. This is an important schema detail to confirm (see Open Decisions).

### 4.3 Unit conversion

Rules:
- Quantity must be greater than zero.
- Units can convert only within the same family.
- Example: grams to kilograms (`g` to `kg`).
- Do not convert between unrelated families, such as grams and millilitres, without an explicitly defined conversion rule.

The source names `UnitConversionService` as the component responsible for conversion.

### 4.4 Versioning

Source-defined rules:
- Only one recipe version is active at a time.
- Changing a recipe affects future sales only.

Historical sales must not be recalculated using the newly edited recipe.

---

## 5. Business Rules

1. A recipe links a product to its raw ingredients.
2. Each recipe item has an ingredient, quantity, and unit.
3. Recipe quantity must be greater than zero.
4. Unit conversion is allowed only within the same unit family.
5. One recipe version may be active at a time for a product.
6. Changing a recipe affects future sales only.
7. On each paid order, the system deducts the BOM.
8. Deduction per sold item is:

   ```text
   delta = -(recipe_qty * sold_qty)
   ```

9. The calculated deduction is converted to the ingredient's base unit.
10. FEFO batches are consumed oldest expiry first.
11. A missing ingredient must be handled by the inventory deduction workflow.
12. A recipe with zero items causes no deduction and must produce a warning.
13. Recipe information is business-sensitive and requires `recipe:view`.

### 5.1 Example BOM calculation

**[PROPOSED DETAIL — illustrative calculation]**

Suppose one portion of a dish requires:
- 100 g paneer
- 20 g onion

If 3 portions are sold:
- Paneer deduction = `-(100 × 3) = -300 g`
- Onion deduction = `-(20 × 3) = -60 g`

If the ingredient's base unit is KG:
- Paneer deduction = `-0.300 KG`
- Onion deduction = `-0.060 KG`

The actual conversion factor must come from the unit conversion rules, not from an arbitrary conversion between unrelated unit families.

---

## 6. User Workflow

### 6.1 Create or update a recipe

1. Manager opens a product.
2. Manager opens the Recipe tab.
3. Manager adds ingredients.
4. Manager enters quantity and unit for each ingredient.
5. Manager defines yield/portion and recipe instructions.
6. Manager saves the recipe.
7. The service validates quantities and units.
8. The recipe version is persisted and the active-version rule is enforced.

Steps 1–6 are the workflow specified by the master documentation. Steps 7–8 are the expected service responsibilities derived from the stated rules.

### 6.2 View recipe

1. Chef opens the relevant product/recipe.
2. The application checks `recipe:view`.
3. Recipe ingredients and cooking information are displayed.
4. Chef can view the training video using the local or URL-based player.

The exact Chef UI is not further specified in the source.

### 6.3 Recipe cost summary

1. User opens Cost Summary.
2. Client requests `GET /api/v1/recipes/{productId}/cost`.
3. Backend calculates or returns the current recipe cost.
4. Cost recalculation job keeps the cost data up to date.

The source confirms the Cost summary screen, cost endpoint, and cost recalculation job. It does not specify the exact cost formula or whether cost is calculated synchronously or from stored calculated values.

### 6.4 BOM deduction after paid order

1. Order becomes paid.
2. Inventory deduction receives the paid-order event/work item.
3. System loads the applicable recipe/BOM for each sold product.
4. Recipe quantity is multiplied by sold quantity.
5. Quantity is converted to the ingredient's base unit.
6. Eligible stock batches are selected using FEFO.
7. Stock movements are recorded and batch quantities are updated.

The source defines the paid-order trigger, deduction formula, base-unit conversion, and FEFO order. The detailed transaction mechanism is owned by the Inventory architecture.

---

## 7. Screens and UI Requirements

### 7.1 Recipe Editor

Source-defined screen.

Required capabilities:
- Display recipe for a product.
- Add ingredients.
- Enter ingredient quantity and unit.
- Maintain yield and portion.
- Maintain cooking steps.
- Maintain prep and cook time.
- Maintain difficulty.
- Maintain training video reference.
- Save recipe.

Source-defined events:
- `RecipeLoaded`
- `IngredientAdded`
- `RecipeSaved`

**[PROPOSED DETAIL]** Suggested editor sections:
1. Recipe header — product, version, active state.
2. Ingredients — ingredient, quantity, unit.
3. Yield and portion.
4. Cooking steps.
5. Time and difficulty.
6. Training video.

### 7.2 Cost Summary

Source-defined screen.

Purpose:
- Show the calculated cost for a product's recipe.

The source does not prescribe:
- Exact cost breakdown fields.
- Whether the cost is per batch or per portion.
- Whether labour, overhead, wastage, or packaging are included.

These should not be silently assumed.

### 7.3 Video Player

Source-defined screen.

Supports:
- Local video.
- URL-based video.

The source does not specify supported video formats, URL providers, or offline download behaviour.

---

## 8. Backend Design

### 8.1 Components

| Component | Responsibility |
|---|---|
| `RecipeController` | Handle recipe API requests |
| `RecipeService` | Recipe business rules, versioning, and persistence |
| `UnitConversionService` | Convert compatible units within the same family |
| Inventory deduction process | Apply BOM deductions to stock |
| Cost recalculation job | Recalculate recipe cost |

### 8.2 `RecipeController`

Source-defined component.

Responsibilities:
- Expose recipe retrieval/update API.
- Expose recipe cost API.
- Delegate business logic to `RecipeService`.

### 8.3 `RecipeService`

Source-defined component.

**[PROPOSED DETAIL]** Responsibilities:
- Load recipe by product ID.
- Add/update recipe items.
- Validate ingredient references.
- Validate positive quantities.
- Enforce one active version at a time.
- Persist recipe header and items consistently.
- Coordinate recipe cost calculation.
- Provide the recipe version applicable to future sales.

### 8.4 `UnitConversionService`

Source-defined component.

Responsibilities:
- Identify the unit family.
- Convert compatible units within the same family.
- Convert recipe quantity into the ingredient's base unit for deduction.
- Reject unsupported cross-family conversions.

The source explicitly gives `g` to `kg` as an example.

### 8.5 Cost recalculation job

The master documentation requires a cost recalculation job and says it must be tested.

The source does not specify:
- Job schedule.
- Trigger strategy.
- Cost formula.
- Whether results are persisted.
- Retry or failure policy.

These are open implementation decisions.

---

## 9. Database Design

### 9.1 Recipe header

The module specifies:

```text
recipes(
  id,
  product_id,
  version,
  steps jsonb,
  prep_min,
  cook_min,
  portion,
  difficulty,
  video_url,
  is_active
)
```

This is the source-defined field list. The master document does not provide a complete `CREATE TABLE recipes` statement, types for every column, constraints, or indexes.

### 9.2 `inventory.recipe_items`

The master documentation's Chapter 6.7 defines:

```sql
CREATE TABLE inventory.recipe_items (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  product_id uuid NOT NULL,
  ingredient_id uuid NOT NULL REFERENCES inventory.ingredients(id),
  quantity numeric(14,4) NOT NULL CHECK (quantity > 0),
  UNIQUE (product_id, ingredient_id)
);
```

| Column | Type | Purpose |
|---|---|---|
| `id` | UUID | Recipe item primary key |
| `tenant_id` | UUID | Tenant ownership |
| `product_id` | UUID | Product associated with the recipe |
| `ingredient_id` | UUID | Ingredient reference |
| `quantity` | numeric(14,4) | Required ingredient quantity; must be positive |

Important schema observations:
- The table is keyed to `product_id`, not `recipe_id`.
- The uniqueness constraint allows one row per `(product_id, ingredient_id)`.
- The shown table does not contain a `unit` column.
- The shown table does not contain a `recipe_version` column.

Do not silently add these columns without confirming the intended versioning and unit model.

### 9.3 `inventory.ingredients`

Chapter 6.7 defines the ingredient master:

```sql
CREATE TABLE inventory.ingredients (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  name varchar(120) NOT NULL,
  uom varchar(10) NOT NULL
    CHECK (uom IN ('KG','G','L','ML','PCS','PACK','BOX','CRATE')),
  low_stock_threshold numeric(14,3) NOT NULL DEFAULT 0,
  is_perishable boolean NOT NULL DEFAULT false,
  version integer NOT NULL DEFAULT 1,
  deleted_at timestamptz,
  UNIQUE (tenant_id, name)
);
```

The ingredient's `uom` is the unit of measure stored on the ingredient master.

### 9.4 Related inventory tables

M9 relies on these inventory tables for BOM deduction:

**`inventory.stock_batches`**

Stores branch-level ingredient batches, quantity on hand, expiry date, unit cost, and received timestamp.

**`inventory.stock_movements`**

Append-only stock movement ledger. The source includes movement reasons such as `SALE`, `VOID_WASTE`, `ADJUSTMENT`, and others.

**`inventory.processed_events`**

Stores processed event IDs for idempotent event handling.

These tables are owned by the Inventory module, but are relevant to M9's deduction integration.

### 9.5 Versioning schema gap

The M9 requirements state:
- One active recipe version at a time.
- Recipe changes affect future sales only.

However, the shown `inventory.recipe_items` schema has no `recipe_id` or version field; it stores items by `product_id`. The `recipes` field list includes `version` and `is_active`, but the complete relational structure is not supplied.

**Important:** Before implementing versioned recipe items, confirm how each recipe version owns its ingredient rows. The source alone does not resolve this relationship.

---

## 10. API Contract

The master documentation defines these endpoints:

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/v1/recipes/{productId}` | Retrieve recipe for a product |
| `PUT` | `/api/v1/recipes/{productId}` | Create/update recipe for a product |
| `GET` | `/api/v1/recipes/{productId}/cost` | Retrieve recipe cost |

### 10.1 `GET /api/v1/recipes/{productId}`

Purpose:
- Retrieve recipe details for the given product ID.

The response should represent the recipe associated with the requested product. The source does not define the exact response JSON.

**[PROPOSED DETAIL]** A response may include recipe header, active version, ingredients, quantity/unit, steps, prep/cook time, portion, difficulty, and video URL. Confirm the exact contract before implementation.

### 10.2 `PUT /api/v1/recipes/{productId}`

Purpose:
- Create or update the recipe for a product.

Source-defined recipe content includes:
- Ingredient items.
- Quantity and unit.
- Yield and portion.
- Cooking steps.
- Prep and cook time.
- Difficulty.
- Training video.

**[PROPOSED DETAIL]** Illustrative request shape:

```json
{
  "version": 1,
  "portion": 1,
  "prepMin": 10,
  "cookMin": 15,
  "difficulty": "MEDIUM",
  "steps": [
    {
      "step": 1,
      "instruction": "Prepare the ingredients"
    }
  ],
  "videoUrl": "https://example.com/training-video",
  "items": [
    {
      "ingredientId": "ingredient-uuid",
      "quantity": 100,
      "unit": "G"
    }
  ]
}
```

This is an illustrative payload only. The source does not define the exact JSON property names, step structure, or whether `unit` is stored on the recipe item or derived from ingredient UOM.

### 10.3 `GET /api/v1/recipes/{productId}/cost`

Purpose:
- Return the recipe cost summary for the product.

The source does not define the exact response structure or calculation formula.

**[PROPOSED DETAIL]** The response may expose a total and per-portion cost if those values are confirmed by the business owner. Do not add labour/overhead cost unless separately specified.

---

## 11. BOM Deduction and FEFO

### 11.1 Deduction formula

The source defines:

```text
delta = -(recipe_qty * sold_qty)
```

The result is converted to the ingredient's base unit.

Where:
- `recipe_qty` is the quantity of ingredient required by the recipe for one sold unit/portion.
- `sold_qty` is the quantity sold.
- `delta` is negative because it represents stock consumption.

### 11.2 Base unit conversion

Before applying the stock movement:
1. Determine the recipe quantity and unit.
2. Determine the ingredient's base UOM.
3. Verify both units belong to the same conversion family.
4. Convert the multiplied quantity to the base unit.
5. Apply the negative stock delta.

Example supported by the source: `g` to `kg`.

Do not infer density-based conversion between mass and volume.

### 11.3 FEFO batch selection

The source requires FEFO:
- Select eligible batches for the ingredient and branch.
- Consume the batch with the oldest expiry first.
- Continue to the next eligible batch if the first batch does not cover the required quantity.

The database index supports this access pattern:

```sql
CREATE INDEX ix_batches_fefo
ON inventory.stock_batches
  (branch_id, ingredient_id, expiry_date NULLS LAST);
```

### 11.4 Paid-order trigger

BOM deduction occurs on each paid order.

The payment/order integration must ensure that:
- Only paid sales trigger the sale deduction.
- The same paid-order event is not processed twice.
- Stock movements are traceable to the source order/event.

The inventory architecture defines a `processed_events` table for event idempotency and a transaction boundary for stock deduction.

### 11.5 Missing ingredients and zero-item recipes

Source-defined test cases:
- Missing ingredient.
- Recipe with zero items.

A recipe with zero items:
- Produces no stock deduction.
- Must produce a warning.

The source does not specify the exact missing-ingredient outcome (block payment, mark deduction pending, allow negative stock, or raise an operational alert). Confirm this policy with the Inventory module owner.

---

## 12. Events

The source defines:

| Event | Meaning |
|---|---|
| `RecipeLoaded` | Recipe data has loaded |
| `IngredientAdded` | Ingredient has been added to the recipe |
| `RecipeSaved` | Recipe has been saved |

Retain these names in the application state-management implementation.

The source does not define a dedicated M9 public event name for BOM deduction. The inventory/order event integration should follow the existing event architecture.

---

## 13. Security and Data Integrity

Source-defined:
- Recipe data is business-sensitive.
- `recipe:view` is required.

**[PROPOSED DETAIL]**
- Validate tenant ownership for product and ingredient IDs.
- Do not expose recipes across tenants.
- Restrict recipe writes through the central RBAC policy.
- Validate every ingredient reference before saving.
- Reject zero or negative recipe quantities.
- Keep recipe version activation consistent so two versions cannot both be active for the same product.
- Ensure recipe changes do not rewrite historical sale calculations.
- Avoid exposing private training-video URLs to unauthorized users.

---

## 14. Testing Strategy

The master documentation explicitly calls out:
- Unit conversion.
- Missing ingredient.
- Recipe with zero items (no deduction, warning).
- Cost recalculation job tested.

### 14.1 Unit tests

- Positive quantity accepted.
- Zero quantity rejected.
- Negative quantity rejected.
- Compatible unit conversion (`g` to `kg`).
- Cross-family conversion rejected unless an explicit conversion rule exists.
- One active recipe version per product.
- Recipe update affects future sales only.
- Recipe with zero items returns no deduction and emits a warning condition.

### 14.2 API integration tests

- `GET /api/v1/recipes/{productId}`
- `PUT /api/v1/recipes/{productId}`
- `GET /api/v1/recipes/{productId}/cost`

### 14.3 BOM deduction tests

1. Paid order triggers deduction.
2. Sold quantity multiplies recipe quantity.
3. Deduction is negative.
4. Quantity is converted to the ingredient's base unit.
5. FEFO consumes oldest-expiry batches first.
6. Multiple batches are consumed when one batch is insufficient.
7. Missing ingredient follows the confirmed exception policy.
8. Duplicate paid-order event does not deduct stock twice.
9. Zero-item recipe creates no deduction and produces a warning.

### 14.4 Versioning tests

- New recipe version can be activated.
- Only one version remains active for a product.
- Old sales are not recalculated using the new recipe.
- Future sales use the active recipe version.

### 14.5 Cost tests

- Recipe cost endpoint returns the expected result according to the approved cost formula.
- Cost recalculation job updates the cost result.
- Cost recalculation job failure/retry behaviour follows the approved job policy.

The source requires the cost recalculation job to be tested but does not define its schedule or formula.

---

## 15. Dependencies and Consumers

### Dependencies

- M8 Product Management — product IDs.
- Inventory ingredient master — ingredient IDs and base UOM.
- Unit conversion rules — compatible unit families.
- Order Management — sold product and quantity.
- Payment module — paid-order trigger.
- Inventory stock batches — FEFO consumption.
- Inventory stock movements — append-only deduction ledger.

### Consumers

- Chef — recipe and cooking instructions.
- Inventory deduction — BOM quantities.
- Cost summary — recipe cost.
- Cost recalculation job — updated recipe cost.

---

## 16. Implementation Deliverables

The source identifies:
- Database.
- Backend.
- Flutter editor.

Suggested implementation checklist:

- [ ] Recipe header schema is confirmed and migrated.
- [ ] `inventory.recipe_items` schema is applied.
- [ ] Relationship between recipe versions and recipe items is resolved.
- [ ] Ingredient UOM and recipe item unit behaviour is resolved.
- [ ] Recipe editor is implemented.
- [ ] Ingredient add/update flow is implemented.
- [ ] Positive quantity validation is implemented.
- [ ] Yield and portion fields are implemented.
- [ ] Cooking steps are persisted.
- [ ] Prep and cook time are persisted.
- [ ] Difficulty is persisted.
- [ ] Training video local/URL playback is supported.
- [ ] `RecipeController` is implemented.
- [ ] `RecipeService` is implemented.
- [ ] `UnitConversionService` is implemented.
- [ ] Recipe GET/PUT endpoints are implemented.
- [ ] Recipe cost endpoint is implemented.
- [ ] Cost recalculation job is implemented and tested.
- [ ] Paid-order BOM deduction is connected to Inventory.
- [ ] Deduction is converted to ingredient base UOM.
- [ ] FEFO batch consumption is implemented.
- [ ] Duplicate event processing is prevented.
- [ ] Zero-item recipe produces no deduction and a warning.
- [ ] Required tests pass.

---

## 17. Definition of Done

M9 is complete when:

1. Admin and Manager can create and update a product's recipe.
2. Chef can read recipe information with `recipe:view`.
3. Recipe items support ingredient, positive quantity, and unit.
4. Yield, portion, cooking steps, prep time, cook time, difficulty, and training video are stored.
5. Only one recipe version is active at a time for a product.
6. Recipe changes affect future sales only.
7. Recipe cost can be retrieved through the cost endpoint.
8. Cost recalculation job runs and passes its tests.
9. Paid orders trigger BOM deduction.
10. Deduction quantity follows `delta = -(recipe_qty * sold_qty)`.
11. Deduction is converted to the ingredient's base unit.
12. FEFO consumes the oldest-expiry stock batches first.
13. Missing ingredient handling follows the approved Inventory policy.
14. A recipe with zero items causes no deduction and produces a warning.
15. Unit conversion, missing ingredient, zero-item recipe, and cost recalculation tests pass.

---

## 18. Open Decisions / Items to Confirm

The master documentation does not fully define the following:

1. **Recipe header DDL:** The `recipes(...)` field list is supplied, but complete SQL types, constraints, indexes, and foreign keys are not.
2. **Recipe item version relationship:** `inventory.recipe_items` stores `product_id`, not `recipe_id` or recipe version. Confirm how ingredient rows are associated with each version.
3. **Recipe item unit:** Module requirements specify a unit per recipe item, but the shown `inventory.recipe_items` DDL has no unit column. Confirm whether unit is stored per item or derived from `inventory.ingredients.uom`.
4. **Ingredient base unit:** Confirm whether `ingredients.uom` is always the base unit or whether a separate base UOM is required.
5. **Yield and portion meaning:** Define whether `portion` represents number of servings, serving size, or another measure.
6. **Cost formula:** Define whether cost includes only ingredient cost or also labour, overhead, packaging, and wastage.
7. **Cost calculation timing:** Define whether cost is calculated on request, maintained by a background job, or both.
8. **Cost job schedule:** Define when the recalculation job runs and how failures are retried.
9. **Missing ingredient policy:** Define whether deduction failure blocks an operation, creates a pending deduction, permits negative stock, or raises an alert.
10. **FEFO expiry-null behaviour:** The index sorts null expiry values last, but the exact runtime handling of non-perishable/no-expiry batches should be confirmed.
11. **Recipe update workflow:** Define whether editing an active recipe creates a new version automatically or updates the active version.
12. **Version activation:** Define how the system guarantees only one active version per product.
13. **Video support:** Define allowed video formats/providers and whether local video files are stored on Edge.
14. **Write permission:** Define the exact permission for recipe create/update; only `recipe:view` is explicitly specified.
15. **Recipe with no items:** The source says no deduction and warning; define where the warning is displayed or logged.

---

## 19. Source Traceability

| Requirement | Master documentation location |
|---|---|
| Recipe purpose, automatic stock deduction and cost | Chapter 9, M9.A |
| Recipe fields and business rules | Chapter 9, M9.B |
| Manager workflow and paid-order deduction | Chapter 9, M9.C |
| Recipe editor, cost summary, video player, events | Chapter 9, M9.D |
| `RecipeController`, `RecipeService`, `UnitConversionService` | Chapter 9, M9.E |
| `inventory.recipe_items`, `recipes(...)` | Chapter 9, M9.F |
| Recipe GET/PUT and cost endpoints | Chapter 9, M9.G |
| Deduction formula, base-unit conversion, FEFO | Chapter 9, M9.H |
| Product ID dependency and Inventory consumer | Chapter 9, M9.I |
| `recipe:view` permission | Chapter 9, M9.J |
| Unit conversion, missing ingredient, zero-item recipe tests | Chapter 9, M9.K |
| Cost recalculation job test | Chapter 9, M9.L |
| DB, Backend, Flutter editor deliverables | Chapter 9, M9.M |
| Ingredient and recipe item table definitions | Chapter 6.7, Migration script V4 |
| Inventory stock deduction transaction and event idempotency | Chapter 6.8 |
