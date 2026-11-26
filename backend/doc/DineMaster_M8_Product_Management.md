# M8 — Product Management (CORE)

**Project:** DineMaster  
**Module:** M8 — Product Management  
**Document type:** Module-level technical specification  
**Source:** DineMaster Master Technical Documentation, Chapter 9 (M8) and Chapter 6.5 (Migration script V2)  
**Status:** CORE module

> **Source discipline:** Requirements and database fields below are grounded in the master documentation. Any additional implementation guidance is marked **[PROPOSED DETAIL]**. Where the source does not define a contract or behaviour, it is listed as an open decision rather than treated as a confirmed requirement.

---

## 1. Module Overview

Product Management maintains the restaurant's menu items, including their pricing, tax classification, dietary information, preparation metadata, images, and availability.

**Users:** Admin, Manager.  
**Additional actor:** Chef can toggle product availability.

The module enables an administrator or manager to create a product, upload its image, link it to a recipe from Module 9, and save it. Chef availability changes and inventory out-of-stock conditions can affect whether the product is available for ordering.

### 1.1 Core responsibilities

- Create, read, update, and archive menu products.
- Maintain product name, SKU, price, GST slab, and veg flag.
- Maintain dietary tags, taste classification, and product badges.
- Maintain kitchen station, preparation time, cooking metadata, and training video link.
- Upload and store product images.
- Link products to recipes by product ID.
- Allow Chef to toggle availability.
- Preserve historical order values through order snapshots.
- Support image delivery through CDN or LAN caching for Edge.

---

## 2. Scope

### 2.1 In scope

- Product list with search and filters.
- Product form.
- Product image upload.
- Product CRUD endpoints.
- Product archive endpoint.
- Availability toggle endpoint.
- Recipe association by product ID.
- Tax slab association.
- Product image validation, resizing, and storage.
- Signed URLs for object storage.
- Image caching through CDN or LAN for Edge.
- Validation and security controls described in this document.

### 2.2 Out of scope / related modules

| Concern | Owner |
|---|---|
| Category creation and ordering | M7 Menu and Category Management |
| Recipe and BOM details | M9 Recipe and BOM Management |
| Order creation and order item snapshots | POS / Order Management |
| Stock levels and inventory out-of-stock detection | Inventory module |
| Kitchen ticket routing and execution | KOT / KDS modules |
| Branch-level availability and price overrides | M7 `branch_products` configuration |

M8 owns the product master. It should not duplicate the full responsibilities of these related modules.

---

## 3. Actors and Permissions

| Actor | Responsibility |
|---|---|
| Admin | Create and maintain products |
| Manager | Create and maintain products |
| Chef | Toggle product availability |
| POS | Read product data for order entry |
| Waiter | Read product data for table-side ordering |
| QR ordering client | Read products that are available for the relevant branch |
| Inventory service/module | May set availability to false when stock is out |
| Recipe module | Links recipe records to a product by product ID |

The source identifies Admin and Manager as product-management users and Chef as the actor who toggles availability.

**[PROPOSED DETAIL]** Use explicit product-management and availability permissions in the existing RBAC system. The source does not specify exact permission strings for M8, so do not assume names such as `product:manage` or `product:availability:update` without confirming the permission matrix.

---

## 4. Product Data Requirements

### 4.1 Product fields

The master documentation specifies these product fields:

| Field | Description |
|---|---|
| Name | Menu item name |
| SKU | Product stock keeping identifier |
| Price | Selling price |
| GST slab | Tax classification |
| Veg flag | Whether the product is vegetarian |
| Dietary tags | Jain, Semi Jain, Vegan, Gluten-Free |
| Taste | Sweet, Mild, Spicy, Extra Spicy, Tangy |
| Badges | Chef's Special, Best Seller, New, Seasonal, Recommended |
| Station | Kitchen station routing |
| Prep time | Preparation time |
| Image | Product image |
| Cooking metadata | Additional cooking information |
| Training video link | Video reference for training |

The database migration defines `dietary_tags` and `badges` as text arrays. It defines `station`, `prep_minutes`, and `image_url` on `core.products`.

### 4.2 Enumerated values

The following values are stated in the module requirements:

**Dietary tags**
- Jain
- Semi Jain
- Vegan
- Gluten-Free

**Taste**
- Sweet
- Mild
- Spicy
- Extra Spicy
- Tangy

**Badges**
- Chef's Special
- Best Seller
- New
- Seasonal
- Recommended

The SQL example comments show canonical examples such as `JAIN`, `VEGAN`, `GLUTEN_FREE`, `BEST_SELLER`, and `NEW`.

**[PROPOSED DETAIL]** Keep stable machine values in the database/API and map them to human-readable labels in the UI. Confirm the complete canonical enum list before enforcing strict enum validation, because the SQL comments provide examples rather than a complete enum constraint.

---

## 5. Business Rules

The source-defined rules are:

1. Product price must be greater than or equal to zero.
2. SKU must be unique per tenant.
3. A product that has been used in orders must be archived, not deleted.
4. Product name length must be between 2 and 150 characters.
5. Price must be numeric with 2 decimal places.
6. Order snapshots the product price and name.
7. Recipe links are made by product ID.
8. Inventory out-of-stock can set product availability to false.
9. Image uploads must reject oversized or wrong-type files.
10. Image metadata must be stripped.
11. Object storage must not allow public bucket listing.

### 5.1 Archive instead of delete

When a product has been used in an order, it must not be physically deleted. It should be archived so that historical order references and reporting remain valid.

The API explicitly provides:

`POST /api/v1/products/{id}/archive`

The source does not define the exact archive field or response body.

### 5.2 Historical order snapshot

When an order is created, the order module snapshots the product's:
- Name
- Price

Later product edits must not rewrite those historical order values.

For example, if a product price changes from ₹180.00 to ₹200.00, an already-created order must continue to show the original snapshotted price.

### 5.3 Availability

Chef can toggle product availability. Inventory out-of-stock can also set availability to false.

**[PROPOSED DETAIL]** If multiple sources can change availability (Chef and Inventory), define a single authoritative update policy so one actor does not unintentionally overwrite another actor's restriction. The master documentation does not define precedence or a reason/source field.

---

## 6. User Workflow

### 6.1 Create a product

1. Admin opens Products.
2. Admin selects Add.
3. Admin fills the product form.
4. Admin uploads a product image.
5. Admin links the recipe from Module 9.
6. Admin saves the product.
7. The backend validates and persists the product.
8. The product becomes available to the menu consumers according to its availability and branch configuration.

The first six steps follow the workflow in the master documentation. The final two describe the expected backend outcome.

### 6.2 Update a product

1. Admin or Manager opens the product list.
2. User searches or filters for the required product.
3. User opens the product form.
4. User updates permitted product details.
5. Backend validates and saves the changes.

Existing order snapshots must remain unchanged.

### 6.3 Toggle availability

1. Chef opens the relevant product/availability interface.
2. Chef toggles availability.
3. The backend persists the new availability state.
4. Ordering clients should no longer offer a product that is unavailable.

The source confirms the `AvailabilityToggled` event and the availability endpoint. It does not prescribe the exact Chef screen.

### 6.4 Archive a product

1. Admin or Manager requests product archival.
2. Backend verifies the product exists within the tenant.
3. Product is archived rather than deleted when it has been used in orders.
4. Historical order records continue to reference their snapshots.

---

## 7. Screens and UI Requirements

### 7.1 Product List

Source-defined requirements:
- Search.
- Filter.
- Product list.
- `ProductsLoaded` event.

**[PROPOSED DETAIL]** Suggested columns:
- Product name
- SKU
- Category
- Price
- GST slab
- Veg / non-veg
- Availability
- Updated timestamp

Suggested actions:
- Add product
- Edit product
- Toggle availability (only for authorized roles)
- Archive product

### 7.2 Product Form

The form captures the product fields listed in Section 4.

Source-defined validations:
- Name: 2–150 characters.
- Price: numeric, 2 decimal places.
- Price must be non-negative.

**[PROPOSED DETAIL]** Group the form into sections:
1. Basic details — name, SKU, description, category.
2. Pricing and tax — price, GST slab.
3. Food information — veg flag, dietary tags, taste.
4. Presentation — badges, image.
5. Kitchen information — station, prep time, cooking metadata, training video link.
6. Recipe — link to Module 9.

Do not make optional fields mandatory unless the business owner confirms the rule.

### 7.3 Image Upload

Source-defined requirements:
- Upload image through a dedicated endpoint.
- Maximum file size: 2 MB.
- Accepted formats: JPEG or PNG.
- Validate file type by content.
- Resize the image.
- Store the image.
- Return a URL.

The UI should show upload progress and the resulting image preview.

**[PROPOSED DETAIL]** Provide a clear validation message for unsupported type or file size, and do not replace an existing image until the new upload has been accepted and stored successfully.

---

## 8. Backend Design

### 8.1 Components

| Component | Responsibility |
|---|---|
| `ProductController` | Handle product HTTP requests |
| `ProductService` | Apply product business rules and coordinate persistence |
| `ImageStorageService` | Validate, resize, store images, and return URLs |
| `core.products` | Product master persistence |
| `product_media` | Product media metadata |
| Object storage | Store uploaded image objects |
| CDN or LAN cache | Deliver product images to Edge clients |

### 8.2 `ProductController`

Source-defined controller.

Responsibilities:
- Accept product requests.
- Invoke `ProductService`.
- Return API responses.
- Expose product CRUD, archive, availability, and image-upload routes.

**[PROPOSED DETAIL]** Keep business rules out of the controller. The controller should not directly execute SQL or implement SKU uniqueness logic.

### 8.3 `ProductService`

Source-defined service.

**[PROPOSED DETAIL]** Responsibilities:
- Validate product name and price.
- Validate category and tax slab references.
- Enforce tenant-level SKU uniqueness.
- Create and update product records.
- Archive products that must not be deleted.
- Update product availability.
- Coordinate recipe linking by product ID.
- Preserve consistency with order snapshot behaviour.

### 8.4 `ImageStorageService`

Source-defined service using object storage with signed URLs.

Responsibilities:
- Validate file content/type.
- Enforce the 2 MB maximum.
- Resize image.
- Store image object.
- Return an image URL.

The source requires signed URLs for object storage.

---

## 9. Database Design

### 9.1 `core.products`

The master documentation's Chapter 6.5 defines:

```sql
CREATE TABLE core.products (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  category_id uuid NOT NULL REFERENCES core.categories(id),
  name varchar(150) NOT NULL,
  description text,
  sku varchar(40),
  price numeric(12,2) NOT NULL CHECK (price >= 0),
  tax_slab_id uuid NOT NULL REFERENCES core.tax_slabs(id),
  is_veg boolean NOT NULL DEFAULT true,
  dietary_tags text[] NOT NULL DEFAULT '{}',
  badges text[] NOT NULL DEFAULT '{}',
  station varchar(30),
  prep_minutes smallint,
  is_available boolean NOT NULL DEFAULT true,
  image_url text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version integer NOT NULL DEFAULT 1,
  deleted_at timestamptz
);

CREATE UNIQUE INDEX ux_products_sku
  ON core.products (tenant_id, sku)
  WHERE sku IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX ix_products_cat
  ON core.products (tenant_id, category_id)
  WHERE deleted_at IS NULL;
```

### 9.2 Column reference

| Column | Type | Purpose |
|---|---|---|
| `id` | UUID | Product primary key |
| `tenant_id` | UUID | Tenant ownership |
| `category_id` | UUID | Category reference |
| `name` | varchar(150) | Product name |
| `description` | text | Product description |
| `sku` | varchar(40) | Product SKU |
| `price` | numeric(12,2) | Selling price; must be non-negative |
| `tax_slab_id` | UUID | Reference to `core.tax_slabs` |
| `is_veg` | boolean | Veg flag |
| `dietary_tags` | text[] | Dietary classifications |
| `badges` | text[] | Product badges |
| `station` | varchar(30) | Kitchen routing station |
| `prep_minutes` | smallint | Preparation time |
| `is_available` | boolean | Product availability |
| `image_url` | text | Product image URL |
| `created_at` | timestamptz | Creation timestamp |
| `updated_at` | timestamptz | Last update timestamp |
| `version` | integer | Record version |
| `deleted_at` | timestamptz, nullable | Soft-delete/archive-related timestamp |

### 9.3 SKU uniqueness

The migration defines a partial unique index:

```sql
CREATE UNIQUE INDEX ux_products_sku
ON core.products (tenant_id, sku)
WHERE sku IS NOT NULL AND deleted_at IS NULL;
```

This means:
- The same non-null SKU cannot be used by two active product records within the same tenant.
- Different tenants may use the same SKU.
- Rows with `deleted_at` set are excluded from this index.

The source business rule says SKU is unique per tenant. Confirm whether archived products should release their SKU before relying on the partial-index behaviour.

### 9.4 `product_media`

The module specifies:

```text
product_media(id, product_id, kind, url)
```

The source does not provide a full DDL definition, foreign-key behaviour, uniqueness rule, or media-kind enum.

**[PROPOSED DETAIL]** Confirm whether `core.products.image_url` is the canonical current image reference and `product_media` stores media history/multiple media records, or whether one of these is intended to be the sole source of truth. Avoid maintaining duplicate image references without a clear ownership rule.

### 9.5 Related tables

- `core.categories` — category relationship.
- `core.tax_slabs` — GST/tax slab relationship.
- Recipe tables from M9 — recipe linked by product ID.
- Order tables — order snapshots preserve historical product name and price.

---

## 10. API Contract

The master documentation defines the following endpoints:

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/v1/products` | List products |
| `POST` | `/api/v1/products` | Create product |
| `GET` | `/api/v1/products/{id}` | Retrieve product |
| `PUT` | `/api/v1/products/{id}` | Update product |
| `POST` | `/api/v1/products/{id}/archive` | Archive product |
| `PUT` | `/api/v1/products/{id}/availability` | Toggle/update availability |
| `POST` | `/api/v1/products/{id}/image` | Upload product image |

### 10.1 `GET /api/v1/products`

Purpose: retrieve products for the product list.

The UI needs search and filter capabilities.

**[PROPOSED DETAIL]** Query parameters such as `search`, `categoryId`, and `isAvailable` may be added if they match the existing API conventions. The master source does not specify their exact names or pagination format.

### 10.2 `POST /api/v1/products`

Purpose: create a product.

**[PROPOSED DETAIL]** Illustrative request body:

```json
{
  "categoryId": "category-uuid",
  "name": "Paneer Tikka",
  "description": "Grilled paneer starter",
  "sku": "STARTER-001",
  "price": 220.00,
  "taxSlabId": "tax-slab-uuid",
  "isVeg": true,
  "dietaryTags": ["JAIN"],
  "badges": ["BEST_SELLER"],
  "station": "GRILL",
  "prepMinutes": 15,
  "isAvailable": true
}
```

This is an illustrative payload, not a source-prescribed API schema. Confirm exact JSON field names and allowed values against the backend contract.

### 10.3 `GET /api/v1/products/{id}`

Purpose: retrieve one product by ID.

**[PROPOSED DETAIL]** Verify tenant ownership before returning the record. Return the product fields required by the edit form.

### 10.4 `PUT /api/v1/products/{id}`

Purpose: update product information.

Business rules:
- Validate name and price.
- Enforce SKU uniqueness per tenant.
- Updating price must not change old order snapshots.

### 10.5 `POST /api/v1/products/{id}/archive`

Purpose: archive a product.

Business rule:
- A product used in orders is archived, not deleted.

The source does not specify the exact response schema or whether every product must be archived rather than physically deleted. Preserve the stated rule for products already used in orders.

### 10.6 `PUT /api/v1/products/{id}/availability`

Purpose: update product availability.

Actors include Chef; inventory out-of-stock can also set availability to false.

**[PROPOSED DETAIL]** Illustrative request:

```json
{
  "isAvailable": false
}
```

The source does not define the request body or whether the endpoint accepts a reason/source field.

### 10.7 `POST /api/v1/products/{id}/image`

Purpose: upload a product image.

Source-defined contract:
- Content type: multipart.
- Maximum size: 2 MB.
- Accepted formats: JPEG or PNG.

Processing:
1. Receive multipart file.
2. Validate actual file content/type.
3. Reject oversized or unsupported files.
4. Resize image.
5. Store in object storage.
6. Return URL.

**[PROPOSED DETAIL]** The API should return a clear validation error for unsupported content and should not expose internal object-storage credentials.

---

## 11. Image Processing and Storage

### 11.1 Required processing

The master documentation requires:
- Content-based type validation.
- Image resizing.
- Object storage.
- URL returned to the caller.
- Signed URLs.
- Maximum upload size of 2 MB.
- JPEG and PNG only.
- Metadata stripping.
- No public bucket listing.

### 11.2 Security requirements

| Risk | Required control |
|---|---|
| File extension spoofing | Validate file type by content |
| Oversized upload | Reject files over 2 MB |
| Unsupported image type | Accept JPEG or PNG only |
| EXIF/location metadata exposure | Strip metadata |
| Public object enumeration | Disable public bucket listing |
| Unauthorized object access | Use signed URLs as specified |

### 11.3 Edge image delivery

The source states:

**CDN or LAN caching of images for the Edge.**

This is required to support efficient image delivery to Edge devices.

**[PROPOSED DETAIL]** Use cache headers and stable image URLs according to the project's object-storage/CDN setup. Define how updated images invalidate cached copies; the source does not prescribe a cache key or invalidation mechanism.

---

## 12. Events

The source defines these events:

| Event | Meaning |
|---|---|
| `ProductsLoaded` | Product list has loaded |
| `ProductSaved` | Product has been saved |
| `AvailabilityToggled` | Product availability has changed |

Retain these event names in the application state-management implementation.

**[PROPOSED DETAIL]** Follow the existing application event convention for payload shape and error states. Do not invent additional public event names without aligning them with the architecture.

---

## 13. Security and Data Integrity

Source-defined controls:
- SKU unique per tenant.
- Product price must be non-negative.
- Reject oversized or wrong-type image files.
- Strip image metadata.
- No public bucket listing.
- Products used in orders must be archived, not deleted.
- Order snapshots protect historical name and price.

**[PROPOSED DETAIL]**
- Derive `tenant_id` from the authenticated principal, not from an untrusted request body.
- Validate that category and tax slab IDs belong to the same tenant.
- Check tenant ownership for every product read/update/archive/availability operation.
- Restrict product management and availability changes through the existing RBAC matrix.
- Never accept a client-supplied image URL as proof that an upload passed validation.
- Avoid logging signed URLs or other temporary access credentials.

---

## 14. Testing Strategy

The master documentation explicitly calls out:
- Price edit does not change old orders.
- SKU clash.
- Image abuse.
- CDN or LAN caching of images for Edge.

### 14.1 Unit tests

- Product name length minimum and maximum.
- Negative price rejection.
- Two-decimal price validation.
- SKU uniqueness within a tenant.
- Same SKU allowed across different tenants.
- Archive behaviour for products used in orders.
- Availability update.
- Product-to-recipe association by product ID.

### 14.2 API integration tests

- `GET /api/v1/products`
- `POST /api/v1/products`
- `GET /api/v1/products/{id}`
- `PUT /api/v1/products/{id}`
- `POST /api/v1/products/{id}/archive`
- `PUT /api/v1/products/{id}/availability`
- `POST /api/v1/products/{id}/image`

### 14.3 Image upload tests

1. Valid JPEG under 2 MB is accepted.
2. Valid PNG under 2 MB is accepted.
3. File over 2 MB is rejected.
4. Unsupported file type is rejected.
5. A renamed non-image file is rejected by content validation.
6. Image is resized.
7. Metadata is stripped.
8. Image is stored and a URL is returned.
9. Bucket listing is not public.
10. Signed URL access behaves according to storage configuration.

### 14.4 Order snapshot tests

1. Create an order using a product at price A.
2. Update the product price to price B.
3. Read the old order.
4. Confirm the old order still shows the snapshotted product name and price A.

### 14.5 Availability tests

- Chef can toggle availability according to assigned authorization.
- Unavailable product is not offered for new ordering.
- Inventory out-of-stock can set availability to false.
- Availability state is persisted and reflected to menu consumers.

The exact conflict/precedence policy between Chef and Inventory remains an open decision.

### 14.6 Edge image cache tests

- Image can be fetched from the configured CDN or LAN cache.
- Edge can reuse cached image data according to the configured cache policy.
- Updated image eventually becomes visible after cache refresh/invalidation.

---

## 15. Dependencies and Consumers

### Dependencies

- M7 Menu and Category Management — category association and branch menu configuration.
- Tax configuration — `core.tax_slabs`.
- M9 Recipe and BOM Management — recipe linked by product ID.
- Object storage — image persistence and signed URLs.
- Existing authentication and RBAC — actor authorization.

### Consumers

- POS
- Waiter
- QR ordering
- Recipe module
- Inventory module
- Edge image cache

---

## 16. Implementation Deliverables

The source identifies:
- Database.
- Backend.
- Object storage.
- Flutter form.

Suggested implementation checklist:

- [ ] `core.products` migration is applied.
- [ ] SKU unique index is applied.
- [ ] `product_media` schema is confirmed and implemented.
- [ ] Product list with search and filter is implemented.
- [ ] Product form is implemented.
- [ ] Name validation (2–150) is implemented.
- [ ] Price validation (non-negative, 2 decimals) is implemented.
- [ ] SKU uniqueness per tenant is enforced.
- [ ] Product create/read/update endpoints are implemented.
- [ ] Product archive endpoint is implemented.
- [ ] Availability endpoint is implemented.
- [ ] Chef availability workflow is connected.
- [ ] Recipe is linked by product ID.
- [ ] Image upload accepts multipart JPEG/PNG up to 2 MB.
- [ ] Image content validation is implemented.
- [ ] Image resizing and metadata stripping are implemented.
- [ ] Object storage uses signed URLs.
- [ ] Public bucket listing is disabled.
- [ ] CDN or LAN image caching for Edge is configured.
- [ ] Historical order snapshots remain unchanged after product edits.
- [ ] Required tests pass.

---

## 17. Definition of Done

M8 is complete when:

1. Admin and Manager can create, view, and update products.
2. Product list supports search and filter.
3. Product form captures the defined product fields.
4. Product name validation enforces 2–150 characters.
5. Price is non-negative and stored/validated to 2 decimal places.
6. SKU uniqueness is enforced per tenant.
7. Products used in orders are archived rather than deleted.
8. Chef can toggle product availability.
9. Inventory out-of-stock can set availability to false.
10. Product images accept only content-validated JPEG/PNG files up to 2 MB.
11. Images are resized, metadata is stripped, and objects are stored securely.
12. Image upload returns a URL and object storage uses signed URLs.
13. Product recipe association uses product ID.
14. Existing order snapshots retain the original product name and price after product edits.
15. CDN or LAN image caching is available for Edge.
16. SKU clash, price snapshot, image abuse, and image cache tests pass.

---

## 18. Open Decisions / Items to Confirm

The master documentation does not fully define these details:

1. **SKU optionality:** The database allows `sku` to be null. Confirm whether SKU is optional in the UI.
2. **SKU reuse after archive:** The partial unique index excludes rows with `deleted_at` set. Confirm whether an archived product's SKU can be reused.
3. **Archive implementation:** Confirm whether archival sets `deleted_at`, `is_available = false`, or uses another status. The product table shown has no explicit `is_archived` column.
4. **Product availability authority:** Define how Chef availability changes interact with Inventory out-of-stock changes.
5. **Branch availability:** Confirm whether `is_available` on `core.products` is a tenant/global default and how it combines with M7 `branch_products.is_available`.
6. **Price override:** Confirm whether branch-level `price_override` takes precedence over `core.products.price`.
7. **GST slab rules:** The source links `tax_slab_id` to `core.tax_slabs`, but does not define which GST slabs are allowed for each product.
8. **Taste and badge canonical values:** Confirm machine-readable enum values and whether custom values are allowed.
9. **Cooking metadata:** Define its exact fields and storage format.
10. **Training video link:** Define accepted URL types and whether videos are uploaded or externally hosted.
11. **`product_media` relationship:** Define whether it stores multiple media items, image history, or only the current image.
12. **Image resizing:** Define output dimensions, formats, and thumbnail variants.
13. **Signed URL expiry:** Define expiry duration and whether URLs are generated on product read or image upload.
14. **Cache invalidation:** Define how CDN/LAN cached images are refreshed after image replacement.
15. **Product API filters and pagination:** Define query parameter names, sort order, and pagination response.
16. **Availability endpoint payload:** Define request body, allowed roles, and whether a reason/source is recorded.

---

## 19. Source Traceability

| Requirement | Master documentation location |
|---|---|
| Product purpose and users | Chapter 9, M8.A |
| Product fields and business rules | Chapter 9, M8.B |
| Admin create/upload/recipe-link workflow | Chapter 9, M8.C |
| Product list, form, image upload, validations, events | Chapter 9, M8.D |
| `ProductController`, `ProductService`, `ImageStorageService` | Chapter 9, M8.E |
| `core.products`, `product_media` | Chapter 9, M8.F |
| Product API endpoints and image upload constraints | Chapter 9, M8.G |
| Image content validation, resize, store, return URL | Chapter 9, M8.H |
| Order snapshots, recipe link, inventory availability | Chapter 9, M8.I |
| Image security controls | Chapter 9, M8.J |
| Price snapshot, SKU clash, image abuse tests | Chapter 9, M8.K |
| CDN or LAN image caching for Edge | Chapter 9, M8.L |
| DB, Backend, object storage, Flutter form deliverables | Chapter 9, M8.M |
| Product table and indexes | Chapter 6.5, Migration script V2 |
