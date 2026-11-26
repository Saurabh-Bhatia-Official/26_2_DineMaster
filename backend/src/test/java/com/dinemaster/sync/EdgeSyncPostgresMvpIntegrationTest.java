package com.dinemaster.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.dinemaster.auth.service.EdgeHostCredentialService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicitly enabled against a disposable migrated PostgreSQL database only. */
@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"})
@EnabledIfSystemProperty(named = "m28.pg.integration", matches = "true")
class EdgeSyncPostgresMvpIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EdgeSyncService sync;
    private UUID branchSyncId = UUID.randomUUID();
    private UUID categorySyncId = UUID.randomUUID();
    private UUID productSyncId = UUID.randomUUID();
    private UUID orderSyncId = UUID.randomUUID();
    private UUID itemSyncId = UUID.randomUUID();
    private UUID hostId = UUID.randomUUID();
    private long tenantId;
    private long branchId;
    private EdgeHostCredentialService.HostScope scope;

    @BeforeEach
    void seedSyntheticFixture() throws Exception {
        tenantId = jdbc.queryForObject("insert into tenants(name,code,active) values (?,?,true) returning id",
                Long.class, "M28 disposable sync fixture", "m28-" + UUID.randomUUID());
        branchId = jdbc.queryForObject("insert into restaurants(name,tenant_id,code,sync_id) values (?,?,?,?) returning id",
                Long.class, "M28 disposable branch", tenantId, "M28" + UUID.randomUUID().toString().substring(0, 10), branchSyncId);
        long taxSlabId = jdbc.queryForObject("insert into tax_slabs(tenant_id,name,rate_percent,is_active) values (?,?,0,true) returning id",
                Long.class, tenantId, "M28 zero tax");
        jdbc.update("insert into categories(name,restaurant_id,tenant_id,sync_id) values (?,?,?,?)",
                "M28 category", branchId, tenantId, categorySyncId);
        jdbc.update("insert into products(name,price,category,is_veg,gst_percentage,is_available,restaurant_id,category_id,tenant_id,tax_slab_id,sync_id) "
                        + "values ('M28 fixture product',12.50,'M28 category',1,0,true,?,?,?,?,?)",
                branchId, jdbc.queryForObject("select id from categories where sync_id=?", Long.class, categorySyncId),
                tenantId, taxSlabId, productSyncId);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(pair.getPublic().getEncoded()));
        jdbc.update("insert into ops.edge_hosts(id,tenant_id,branch_id,public_key_pem,public_key_sha256,state,sync_epoch) "
                        + "values (?,?,?,?,?,'ACTIVE',1)", hostId, tenantId, branchId, publicPem, fingerprint);
        var credentials = new EdgeHostCredentialService(jdbc, privatePem, publicPem);
        var tokens = credentials.createInitial(hostId, tenantId, branchId, 1);
        scope = credentials.validateAccess(tokens.accessToken());
        assertThat(scope).isNotNull();
    }

    @AfterEach
    void removeSyntheticFixture() {
        jdbc.update("delete from ops.edge_processed_changes where host_id=?", hostId);
        jdbc.update("delete from ops.edge_sync_cursors where host_id=?", hostId);
        jdbc.update("delete from ops.edge_host_refresh_tokens where host_id=?", hostId);
        jdbc.update("delete from ops.edge_host_refresh_families where host_id=?", hostId);
        jdbc.update("delete from ops.edge_hosts where id=?", hostId);
        jdbc.update("delete from ops.sync_identity_map where sync_id in (?,?)", orderSyncId, itemSyncId);
        jdbc.update("delete from order_items where sync_id=?", itemSyncId);
        jdbc.update("delete from orders where sync_id=?", orderSyncId);
        jdbc.update("delete from products where sync_id=?", productSyncId);
        jdbc.update("delete from categories where sync_id=?", categorySyncId);
        jdbc.update("delete from tax_slabs where tenant_id=? and name='M28 zero tax'", tenantId);
        jdbc.update("delete from restaurants where id=?", branchId);
        jdbc.update("delete from tenants where id=?", tenantId);
    }

    @Test
    void pushesOrderBeforeItemPullsCatalogAndPreventsDuplicateOrChangedReplay() {
        UUID orderChangeId = UUID.randomUUID();
        Map<String, Object> order = new HashMap<>();
        order.put("sync_id", orderSyncId.toString());
        order.put("local_id", 701);
        order.put("version", 1);
        order.put("total_amount", "0.00");
        order.put("discount_amount", "0.00");
        order.put("status", "Pending");
        order.put("type", "Takeaway");
        order.put("order_time", OffsetDateTime.now().toString());
        var orderChange = new EdgeSyncChangeRequest(orderChangeId, "orders", orderSyncId, "UPSERT", order);

        assertThat(sync.push(scope, List.of(orderChange)).toString()).contains("ACCEPTED");
        assertThat(sync.push(scope, List.of(orderChange)).toString()).contains("ACCEPTED");
        order.put("total_amount", "9.99");
        assertThat(sync.push(scope, List.of(orderChange)).toString()).contains("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD");
        assertThat(jdbc.queryForObject("select count(*) from orders where sync_id=?", Integer.class, orderSyncId)).isEqualTo(1);

        Map<String, Object> item = new HashMap<>();
        item.put("sync_id", itemSyncId.toString());
        item.put("local_id", 702);
        item.put("order_sync_id", orderSyncId.toString());
        item.put("product_sync_id", productSyncId.toString());
        item.put("product_version", 1);
        item.put("quantity", 2);
        item.put("price", "12.50");
        item.put("status", "Pending");
        item.put("version", 1);
        var itemChange = new EdgeSyncChangeRequest(UUID.randomUUID(), "order_items", itemSyncId, "UPSERT", item);
        assertThat(sync.push(scope, List.of(itemChange)).toString()).contains("ACCEPTED");
        assertThat(jdbc.queryForObject("select count(*) from order_items where sync_id=?", Integer.class, itemSyncId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select total_amount from orders where sync_id=?", java.math.BigDecimal.class, orderSyncId))
                .isEqualByComparingTo("25.00");
        assertThat(jdbc.queryForObject("select count(*) from ops.sync_identity_map where sync_id in (?,?) and mapping_status='MATCHED'",
                Integer.class, orderSyncId, itemSyncId)).isEqualTo(2);

        Map<String, Object> page = sync.pullMenu(scope, "", 100);
        List<String> entities = ((List<?>) page.get("items")).stream()
                .map(itemRow -> ((Map<?, ?>) itemRow).get("entityType").toString()).toList();
        assertThat(entities).contains("categories", "products");
        String cursor = page.get("nextCursor").toString();
        sync.acknowledgeCursor(scope, cursor);
        sync.acknowledgeCursor(scope, cursor);
        assertThat(sync.status(scope).get("state")).isEqualTo("ACTIVE");
    }

    private String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END " + label + "-----";
    }
}
