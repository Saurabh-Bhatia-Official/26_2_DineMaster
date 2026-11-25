package com.dinemaster.sync;

import com.dinemaster.auth.service.EdgeHostCredentialService.HostScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class EdgeSyncService {
    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EdgeSyncService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public Map<String, Object> push(HostScope host, List<EdgeSyncChangeRequest> changes) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (EdgeSyncChangeRequest change : changes) results.add(process(host, change));
        return Map.of("results", results);
    }

    private Map<String, Object> process(HostScope host, EdgeSyncChangeRequest change) {
        String requestHash = requestHash(change);
        var prior = jdbc.query("select host_id,result,response::text,request_hash from ops.edge_processed_changes where change_id=?",
                (rs, n) -> new Prior(rs.getObject("host_id", UUID.class), rs.getString("result"), rs.getString("response"), rs.getString("request_hash")), change.changeId());
        if (!prior.isEmpty()) {
            Prior receipt = prior.get(0);
            if (!host.hostId().equals(receipt.hostId)) return result(change.changeId(), "REJECTED", "CHANGE_ID_OWNED_BY_OTHER_HOST");
            if (receipt.requestHash == null || !requestHash.equals(receipt.requestHash.trim()))
                return result(change.changeId(), "REJECTED", "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD");
            try { return json.readValue(receipt.response, new TypeReference<>() { }); }
            catch (Exception e) { throw new IllegalStateException("Stored sync receipt is invalid", e); }
        }
        String outcome;
        String detail = "";
        try {
            if (!"UPSERT".equals(change.operation())) { outcome = "REJECTED"; detail = "UNSUPPORTED_OPERATION"; }
            else if ("orders".equals(change.entityType())) { outcome = upsertOrder(host, change); detail = outcome.equals("ACCEPTED") ? "" : "ORDER_CONFLICT_OR_INVALID"; }
            else if ("order_items".equals(change.entityType())) { outcome = upsertOrderItem(host, change); detail = outcome.equals("ACCEPTED") ? "" : "ITEM_DEPENDENCY_OR_VERSION_CONFLICT"; }
            else { outcome = "REJECTED"; detail = "ENTITY_NOT_ALLOWED"; }
        } catch (IllegalArgumentException invalid) {
            outcome = "REJECTED"; detail = "PAYLOAD_INVALID";
        }
        Map<String, Object> response = result(change.changeId(), outcome, detail);
        jdbc.update("insert into ops.edge_processed_changes(change_id,host_id,result,response,request_hash) values (?,?,?,?::jsonb,?)",
                change.changeId(), host.hostId(), outcome, writeJson(response), requestHash);
        return response;
    }

    private String upsertOrder(HostScope host, EdgeSyncChangeRequest change) {
        Map<String, Object> p = change.payload(); requirePayloadIdentity(change, p);
        long version = positiveLong(p.get("version"));
        String status = string(p.get("status"), "Pending", 30);
        if (!List.of("Pending", "Received").contains(status)) return "REJECTED";
        BigDecimal discount = decimalOrZero(p.get("discount_amount"));
        if (discount.signum() < 0 || discount.scale() > 2) return "REJECTED";
        UUID syncId = change.entitySyncId();
        var existing = jdbc.query("select id,version,tenant_id,restaurant_id from orders where sync_id=? for update",
                (rs, n) -> new Existing(rs.getLong("id"), rs.getLong("version"), rs.getLong("tenant_id"), rs.getLong("restaurant_id")), syncId);
        if (existing.isEmpty()) {
            String orderNo = "E-" + syncId;
            jdbc.update("insert into orders(sync_id,tenant_id,restaurant_id,order_no,total_amount,subtotal,discount_amount,"
                    + "tax_amount,round_off,discount_type,discount_value,parked,status,type,order_time,notes,channel,payment_status,"
                    + "customer_name,customer_phone,guest_count,version) values (?,?,?, ?,0,0,?,0,0,'FLAT',?,false,?,?,?,?,'STAFF','Unpaid',?,?,?,?)",
                    syncId, host.tenantId(), host.branchId(), orderNo, discount, discount, status,
                    string(p.get("type"), "Takeaway", 30), date(p.get("order_time")), nullableString(p.get("notes"), 2000),
                    nullableString(p.get("customer_name"), 150), nullableString(p.get("customer_phone"), 30),
                    nullableInteger(p.get("guest_count")), version);
            mapIdentity("orders", syncId, nullableLong(p.get("local_id")), host.hostId());
            return "ACCEPTED";
        }
        Existing row = existing.get(0);
        if (row.tenantId != host.tenantId() || row.branchId != host.branchId() || version != row.version + 1) return "CONFLICT";
        Money currentMoney = currentMoney(row.id, null);
        if (discount.compareTo(currentMoney.subtotal.add(currentMoney.tax)) > 0) return "REJECTED";
        int updated = jdbc.update("update orders set discount_amount=?,discount_value=?,total_amount=?,subtotal=?,tax_amount=?,status=?,type=?,order_time=?,"
                        + "notes=?,customer_name=?,customer_phone=?,guest_count=?,version=version where id=? and tenant_id=? and restaurant_id=? and version=?",
                discount, discount, currentMoney.subtotal.add(currentMoney.tax).subtract(discount), currentMoney.subtotal,
                currentMoney.tax, status, string(p.get("type"), "Takeaway", 30),
                date(p.get("order_time")), nullableString(p.get("notes"), 2000), nullableString(p.get("customer_name"), 150),
                nullableString(p.get("customer_phone"), 30), nullableInteger(p.get("guest_count")), row.id,
                host.tenantId(), host.branchId(), row.version);
        if (updated == 1) mapIdentity("orders", syncId, nullableLong(p.get("local_id")), host.hostId());
        return updated == 1 ? "ACCEPTED" : "CONFLICT";
    }

    private String upsertOrderItem(HostScope host, EdgeSyncChangeRequest change) {
        Map<String, Object> p = change.payload(); requirePayloadIdentity(change, p);
        UUID orderSyncId = uuid(p.get("order_sync_id"));
        UUID productSyncId = uuid(p.get("product_sync_id"));
        int quantity = Math.toIntExact(positiveLong(p.get("quantity")));
        long version = positiveLong(p.get("version"));
        var order = jdbc.query("select id from orders where sync_id=? and tenant_id=? and restaurant_id=?",
                (rs, n) -> rs.getLong(1), orderSyncId, host.tenantId(), host.branchId());
        if (order.isEmpty()) return "REJECTED";
        var product = jdbc.query("select id,name,price,gst_percentage,version,tenant_id,restaurant_id,station from products "
                        + "where sync_id=? and tenant_id=? and deleted_at is null and is_available=true and (restaurant_id is null or restaurant_id=?)",
                (rs, n) -> new Product(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("price"),
                        rs.getBigDecimal("gst_percentage"), rs.getLong("version"), rs.getLong("tenant_id"),
                        (Long) rs.getObject("restaurant_id"), rs.getString("station")), productSyncId,
                host.tenantId(), host.branchId());
        if (product.isEmpty()) return "REJECTED";
        Product pRow = product.get(0);
        BigDecimal price = decimal(p.get("price"));
        if (price.compareTo(pRow.price) != 0 || positiveLong(p.get("product_version")) != pRow.version) return "CONFLICT";
        String status = string(p.get("status"), "Pending", 30);
        if (!"Pending".equals(status)) return "REJECTED";
        UUID syncId = change.entitySyncId();
        var exists = jdbc.query("select id,version,order_id,product_id from order_items where sync_id=? for update",
                (rs, n) -> new ItemExisting(rs.getLong("id"), rs.getLong("version"), rs.getLong("order_id"), rs.getLong("product_id")), syncId);
        Long replacingItemId = null;
        if (!exists.isEmpty()) {
            ItemExisting current = exists.get(0);
            if (version != current.version + 1 || current.orderId != order.get(0) || current.productId != pRow.id) return "CONFLICT";
            replacingItemId = current.id;
        }
        Money priorMoney = currentMoney(order.get(0), replacingItemId);
        BigDecimal lineSubtotal = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal lineTax = lineSubtotal.multiply(pRow.gst).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal subtotal = priorMoney.subtotal.add(lineSubtotal);
        BigDecimal tax = priorMoney.tax.add(lineTax);
        BigDecimal discount = jdbc.queryForObject("select discount_amount from orders where id=?", BigDecimal.class, order.get(0));
        if (discount.compareTo(subtotal.add(tax)) > 0) return "REJECTED";
        if (exists.isEmpty()) {
            jdbc.update("insert into order_items(sync_id,order_id,product_id,product_name_snapshot,quantity,price,tax_rate_snapshot,notes,status,station,version) "
                            + "values (?,?,?,?,?,?,?,?,'Pending',?,?)",
                    syncId, order.get(0), pRow.id, pRow.name, quantity, price, pRow.gst, nullableString(p.get("notes"), 1000), pRow.station, version);
        } else {
            ItemExisting current = exists.get(0);
            int updated = jdbc.update("update order_items set quantity=?,price=?,notes=?,version=version where id=? and version=?",
                    quantity, price, nullableString(p.get("notes"), 1000), current.id, current.version);
            if (updated != 1) return "CONFLICT";
        }
        jdbc.update("update orders set subtotal=?,tax_amount=?,total_amount=?,round_off=0 where id=? and tenant_id=? and restaurant_id=?",
                subtotal, tax, subtotal.add(tax).subtract(discount), order.get(0), host.tenantId(), host.branchId());
        mapIdentity("order_items", syncId, nullableLong(p.get("local_id")), host.hostId());
        return "ACCEPTED";
    }

    private Money currentMoney(long orderId, Long excludingItemId) {
        String sql = "select coalesce(sum(quantity*price),0) subtotal,"
                + "coalesce(sum(round(quantity*price*tax_rate_snapshot/100,2)),0) tax from order_items where order_id=?"
                + (excludingItemId == null ? "" : " and id<>?");
        return jdbc.queryForObject(sql, (rs, n) -> new Money(rs.getBigDecimal("subtotal"), rs.getBigDecimal("tax")),
                excludingItemId == null ? new Object[]{orderId} : new Object[]{orderId, excludingItemId});
    }

    @Transactional
    public Map<String, Object> pullMenu(HostScope host, String ignoredClientCursor, int limit) {
        String cursor = jdbc.query("select cursor_value from ops.edge_sync_cursors where host_id=? and stream_key='menu'",
                rs -> rs.next() ? rs.getString(1) : "", host.hostId());
        if (cursor == null || cursor.isBlank()) cursor = "1970-01-01T00:00:00Z|" + ZERO_UUID;
        Cursor after = parseCursor(cursor);
        List<MenuRow> rows = new ArrayList<>();
        rows.addAll(jdbc.query("select id,sync_id,version,updated_at,name,sort_order,is_active from categories "
                        + "where tenant_id=? and deleted_at is null and (restaurant_id is null or restaurant_id=?) "
                        + "and (updated_at, sync_id) > (?,?::uuid) order by updated_at,sync_id limit ?",
                (rs, n) -> new MenuRow("categories", rs.getLong("id"), rs.getObject("sync_id", UUID.class), rs.getLong("version"),
                        rs.getObject("updated_at", OffsetDateTime.class), Map.of("name", rs.getString("name"),
                                "sort_order", rs.getInt("sort_order"), "is_active", rs.getBoolean("is_active"))),
                host.tenantId(), host.branchId(), after.time, after.syncId, limit));
        rows.addAll(jdbc.query("select p.id,p.sync_id,p.version,p.updated_at,p.name,p.description,p.ingredients,p.price,p.category,"
                        + "p.category_id,c.sync_id category_sync_id,p.is_veg,p.image_path,p.is_available,p.gst_percentage,"
                        + "p.dietary_preferences,p.taste_preferences,p.attributes,p.custom_dietary_notes,p.recipe_steps,"
                        + "p.prep_time,p.cook_time,p.servings,p.difficulty,p.video_path,p.station from products p "
                        + "left join categories c on c.id=p.category_id where p.tenant_id=? and p.deleted_at is null "
                        + "and (p.restaurant_id is null or p.restaurant_id=?) and (p.updated_at,p.sync_id)>(?,?::uuid) "
                        + "order by p.updated_at,p.sync_id limit ?",
                (rs, n) -> new MenuRow("products", rs.getLong("id"), rs.getObject("sync_id", UUID.class), rs.getLong("version"),
                        rs.getObject("updated_at", OffsetDateTime.class), Map.ofEntries(
                                Map.entry("name", rs.getString("name")), Map.entry("description", val(rs.getString("description"))),
                                Map.entry("ingredients", val(rs.getString("ingredients"))), Map.entry("price", rs.getBigDecimal("price")),
                                Map.entry("category", rs.getString("category")), Map.entry("category_sync_id", val(rs.getString("category_sync_id"))),
                                Map.entry("is_veg", rs.getInt("is_veg")), Map.entry("image_path", val(rs.getString("image_path"))),
                                Map.entry("is_available", rs.getBoolean("is_available")), Map.entry("gst_percentage", rs.getBigDecimal("gst_percentage")),
                                Map.entry("dietary_preferences", val(rs.getString("dietary_preferences"))), Map.entry("taste_preferences", val(rs.getString("taste_preferences"))),
                                Map.entry("attributes", val(rs.getString("attributes"))), Map.entry("custom_dietary_notes", val(rs.getString("custom_dietary_notes"))),
                                Map.entry("recipe_steps", val(rs.getString("recipe_steps"))), Map.entry("prep_time", val(rs.getObject("prep_time"))),
                                Map.entry("cook_time", val(rs.getObject("cook_time"))), Map.entry("servings", val(rs.getObject("servings"))),
                                Map.entry("difficulty", val(rs.getString("difficulty"))), Map.entry("video_path", val(rs.getString("video_path"))),
                                Map.entry("station", val(rs.getString("station"))))),
                host.tenantId(), host.branchId(), after.time, after.syncId, limit));
        rows.sort(Comparator.comparing(MenuRow::updatedAt).thenComparing(r -> r.syncId.toString()));
        if (rows.size() > limit) rows = new ArrayList<>(rows.subList(0, limit));
        String next = rows.isEmpty() ? cursor : cursor(rows.get(rows.size() - 1));
        jdbc.update("insert into ops.edge_sync_cursors(host_id,stream_key,cursor_value,pending_cursor,updated_at) values (?, 'menu', ?, ?, CURRENT_TIMESTAMP) "
                        + "on conflict(host_id,stream_key) do update set pending_cursor=excluded.pending_cursor,updated_at=CURRENT_TIMESTAMP",
                host.hostId(), cursor, next);
        List<Map<String, Object>> items = rows.stream().map(r -> Map.<String, Object>of("entityType", r.entityType,
                "cloudId", r.cloudId,
                "syncId", r.syncId.toString(), "version", r.version, "updatedAt", r.updatedAt.toInstant().toString(), "data", r.data)).toList();
        return Map.of("items", items, "nextCursor", next, "hasMore", !rows.isEmpty() && !next.equals(cursor));
    }

    @Transactional
    public void acknowledgeCursor(HostScope host, String cursor) {
        if (cursor == null || cursor.isBlank()) throw new IllegalArgumentException("cursor");
        parseCursor(cursor);
        int count = jdbc.update("update ops.edge_sync_cursors set cursor_value=pending_cursor,pending_cursor=null,updated_at=CURRENT_TIMESTAMP "
                        + "where host_id=? and stream_key='menu' and pending_cursor=?",
                host.hostId(), cursor);
        if (count == 0) {
            Integer already = jdbc.queryForObject("select count(*) from ops.edge_sync_cursors where host_id=? and stream_key='menu' and cursor_value=?",
                    Integer.class, host.hostId(), cursor);
            if (already == null || already == 0) throw new IllegalArgumentException("cursor not issued to this host");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(HostScope host) {
        String menuCursor = jdbc.query("select cursor_value from ops.edge_sync_cursors where host_id=? and stream_key='menu'",
                rs -> rs.next() ? rs.getString(1) : "", host.hostId());
        Integer accepted = jdbc.queryForObject("select count(*) from ops.edge_processed_changes where host_id=? and result='ACCEPTED'",
                Integer.class, host.hostId());
        Integer pending = jdbc.queryForObject("select count(*) from ops.edge_processed_changes where host_id=? and result in ('CONFLICT','REJECTED')",
                Integer.class, host.hostId());
        return Map.of("hostId", host.hostId().toString(), "tenantId", host.tenantId(), "branchId", host.branchId(),
                "syncEpoch", host.epoch(), "state", "ACTIVE", "menuCursor", menuCursor == null ? "" : menuCursor,
                "acceptedChanges", accepted == null ? 0 : accepted, "attentionRequired", pending == null ? 0 : pending);
    }

    private void mapIdentity(String type, UUID syncId, Long localId, UUID hostId) {
        if (localId == null) return;
        Integer conflict = jdbc.queryForObject("select count(*) from ops.sync_identity_map where entity_type=? and sync_id=? "
                + "and edge_id is not null and edge_id<>?", Integer.class, type, syncId, localId);
        if (conflict != null && conflict > 0) throw new IllegalArgumentException("identity mapping conflict");
        Long cloudId = jdbc.queryForObject("select id from " + ("orders".equals(type) ? "orders" : "order_items") + " where sync_id=?",
                Long.class, syncId);
        jdbc.update("insert into ops.sync_identity_map(entity_type,sync_id,edge_id,cloud_id,mapping_status,resolution_note,updated_at) "
                        + "values (?,?,?,?,'MATCHED',?,CURRENT_TIMESTAMP) "
                        + "on conflict(entity_type,sync_id) do update set edge_id=excluded.edge_id,cloud_id=excluded.cloud_id,"
                        + "mapping_status='MATCHED',resolution_note=excluded.resolution_note,updated_at=CURRENT_TIMESTAMP",
                type, syncId, localId, cloudId, "Authenticated host " + hostId);
    }
    private Map<String, Object> result(UUID id, String outcome, String detail) {
        return Map.of("changeId", id.toString(), "result", outcome, "detail", detail);
    }
    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String requestHash(EdgeSyncChangeRequest change) {
        Map<String, Object> fingerprint = Map.of("changeId", change.changeId().toString(),
                "entityType", change.entityType(), "entitySyncId", change.entitySyncId().toString(),
                "operation", change.operation(), "payload", sorted(change.payload()));
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(writeJson(fingerprint).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, entry) -> ordered.put(String.valueOf(key), sorted(entry)));
            return ordered;
        }
        if (value instanceof List<?> list) return list.stream().map(this::sorted).toList();
        return value;
    }
    private void requirePayloadIdentity(EdgeSyncChangeRequest change, Map<String, Object> payload) {
        if (!change.entitySyncId().equals(uuid(payload.get("sync_id")))) throw new IllegalArgumentException("sync ID mismatch");
    }
    private String string(Object value, String fallback, int max) {
        String result = value == null ? fallback : value.toString().trim();
        if (result.isEmpty() || result.length() > max) throw new IllegalArgumentException("string");
        return result;
    }
    private String nullableString(Object value, int max) {
        if (value == null) return null;
        String result = value.toString(); if (result.length() > max) throw new IllegalArgumentException("string"); return result;
    }
    private BigDecimal decimal(Object value) { try { return new BigDecimal(value.toString()); } catch (Exception e) { throw new IllegalArgumentException("number", e); } }
    private BigDecimal decimalOrZero(Object value) { return value == null ? BigDecimal.ZERO : decimal(value); }
    private long positiveLong(Object value) { try { long n = Long.parseLong(value.toString()); if (n < 1) throw new NumberFormatException(); return n; } catch (Exception e) { throw new IllegalArgumentException("integer", e); } }
    private Long nullableLong(Object value) { return value == null ? null : Long.valueOf(value.toString()); }
    private Integer nullableInteger(Object value) { return value == null ? null : Math.toIntExact(Long.parseLong(value.toString())); }
    private UUID uuid(Object value) { try { return UUID.fromString(value.toString()); } catch (Exception e) { throw new IllegalArgumentException("uuid", e); } }
    private OffsetDateTime date(Object value) { try { return OffsetDateTime.parse(value.toString()).withOffsetSameInstant(ZoneOffset.UTC); } catch (Exception e) { throw new IllegalArgumentException("timestamp", e); } }
    private Object val(Object value) { return value == null ? "" : value; }
    private Cursor parseCursor(String value) {
        if (value == null || value.isBlank()) return new Cursor(OffsetDateTime.parse("1970-01-01T00:00:00Z"), UUID.fromString(ZERO_UUID));
        String[] parts = value.split("\\|", 2); if (parts.length != 2) throw new IllegalArgumentException("cursor");
        try { return new Cursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1])); }
        catch (Exception e) { throw new IllegalArgumentException("cursor", e); }
    }
    private String cursor(MenuRow row) { return row.updatedAt.toInstant().toString() + "|" + row.syncId; }
    private record Existing(long id, long version, long tenantId, long branchId) { }
    private record ItemExisting(long id, long version, long orderId, long productId) { }
    private record Product(long id, String name, BigDecimal price, BigDecimal gst, long version, long tenantId, Long branchId, String station) { }
    private record Prior(UUID hostId, String result, String response, String requestHash) { }
    private record Money(BigDecimal subtotal, BigDecimal tax) { }
    private record Cursor(OffsetDateTime time, UUID syncId) { }
    private record MenuRow(String entityType, long cloudId, UUID syncId, long version, OffsetDateTime updatedAt, Map<String, Object> data) { }
}
