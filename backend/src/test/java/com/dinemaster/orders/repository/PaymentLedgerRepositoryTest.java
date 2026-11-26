package com.dinemaster.orders.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PaymentLedgerRepositoryTest {
    @Autowired private PaymentLedgerRepository ledger;
    @Autowired private JdbcTemplate jdbc;

    private long tenantId;
    private long branchId;
    private long orderId;

    @BeforeEach
    void createOrderFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO tenants(name, code, active, created_at) VALUES (?, ?, TRUE, ?)",
                "M28 Tenant " + suffix, "M28-T-" + suffix, OffsetDateTime.now());
        tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE code = ?", Long.class,
                "M28-T-" + suffix);
        String branchCode = "M" + suffix.substring(0, 10);
        jdbc.update("""
                INSERT INTO restaurants(name, tenant_id, code, timezone, is_active, version, created_at)
                VALUES (?, ?, ?, 'Asia/Kolkata', TRUE, 1, ?)
                """, "M28 Branch " + suffix, tenantId, branchCode, OffsetDateTime.now());
        branchId = jdbc.queryForObject("SELECT id FROM restaurants WHERE code = ?", Long.class, branchCode);

        String orderNo = "M28-" + suffix;
        jdbc.update("""
                INSERT INTO orders(tenant_id, order_no, restaurant_id, version, channel, total_amount,
                    discount_amount, subtotal, tax_amount, round_off, discount_type, discount_value,
                    parked, status, type, order_time, payment_status)
                VALUES (?, ?, ?, 0, 'STAFF', 100.00, 0.00, 100.00, 0.00, 0.00, 'FLAT', 0.00,
                    FALSE, 'BILLED', 'Dine In', ?, 'Unpaid')
                """, tenantId, orderNo, branchId, OffsetDateTime.now());
        orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
    }

    @Test
    void recordsImmutablePaymentAndReturnsTheSameEventForAnExactRetry() {
        UUID paymentId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        var first = ledger.recordPayment(paymentId, orderId, tenantId, branchId,
                new BigDecimal("100.00"), "INR", "Cash", key, null, null);
        var replay = ledger.recordPayment(paymentId, orderId, tenantId, branchId,
                new BigDecimal("100.00"), "INR", "Cash", key, null, null);

        assertThat(first.replay()).isFalse();
        assertThat(replay.replay()).isTrue();
        assertThat(replay.databaseId()).isEqualTo(first.databaseId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_events WHERE idempotency_key = ?", Integer.class, key))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ops.sync_identity_map WHERE entity_type='payments' AND sync_id = ?",
                Integer.class, paymentId)).isEqualTo(1);
    }

    @Test
    void rejectsChangedRequestForAnExistingIdempotencyKeyAndOutOfScopeOrder() {
        UUID paymentId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        ledger.recordPayment(paymentId, orderId, tenantId, branchId,
                new BigDecimal("40.00"), "INR", "Card", key, null, null);

        assertThatThrownBy(() -> ledger.recordPayment(paymentId, orderId, tenantId, branchId,
                new BigDecimal("41.00"), "INR", "Card", key, null, null))
                .isInstanceOf(PaymentLedgerRepository.IdempotencyConflictException.class);
        assertThatThrownBy(() -> ledger.recordPayment(paymentId, orderId, tenantId,
                branchId + 1, new BigDecimal("40.00"), "INR", "Card", key, null, null))
                .isInstanceOf(PaymentLedgerRepository.IdempotencyConflictException.class);
        assertThatThrownBy(() -> ledger.recordPayment(UUID.randomUUID(), orderId, tenantId,
                branchId + 1, new BigDecimal("40.00"), "INR", "Card",
                UUID.randomUUID().toString(), null, null))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    @Test
    void writesReversalAsANewLinkedEvent() {
        UUID paymentId = UUID.randomUUID();
        var payment = ledger.recordPayment(paymentId, orderId, tenantId, branchId,
                new BigDecimal("100.00"), "INR", "Card", UUID.randomUUID().toString(), null, null);
        UUID reversalId = UUID.randomUUID();
        String reversalKey = UUID.randomUUID().toString();
        var reversal = ledger.recordReversal(reversalId, orderId, tenantId, branchId,
                new BigDecimal("25.00"), "INR", null, reversalKey,
                payment.syncId(), null, null);
        var replay = ledger.recordReversal(reversalId, orderId, tenantId, branchId,
                new BigDecimal("25.00"), "INR", null, reversalKey,
                payment.syncId(), null, null);

        assertThat(reversal.eventType()).isEqualTo("REVERSAL");
        assertThat(replay.replay()).isTrue();
        assertThat(reversal.reversesEventId()).isEqualTo(payment.syncId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_events WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(2);
    }
}
