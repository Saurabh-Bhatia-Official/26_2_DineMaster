package com.dinemaster.orders.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only persistence for M28 payment events. This repository is not yet
 * wired to an order endpoint; the caller must perform the normal user/branch
 * authorization before invoking it.
 */
@Repository
public class PaymentLedgerRepository {
    private final JdbcTemplate jdbc;

    public PaymentLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PaymentEventResult recordPayment(UUID syncId, long orderId, long tenantId,
            long branchId, BigDecimal amount, String currency, String paymentMethod,
            String idempotencyKey, UUID sourceDeviceId, Long actorUserId) {
        validate(syncId, amount, currency, paymentMethod, idempotencyKey, true);
        return record("PAYMENT", syncId, orderId, tenantId, branchId, amount,
                currency, paymentMethod, idempotencyKey, null, sourceDeviceId, actorUserId);
    }

    @Transactional
    public PaymentEventResult recordReversal(UUID syncId, long orderId, long tenantId,
            long branchId, BigDecimal amount, String currency, String paymentMethod,
            String idempotencyKey, UUID reversesEventId, UUID sourceDeviceId,
            Long actorUserId) {
        validate(syncId, amount, currency, paymentMethod, idempotencyKey, false);
        if (reversesEventId == null) {
            throw new IllegalArgumentException("A reversal must identify the original payment event");
        }
        return record("REVERSAL", syncId, orderId, tenantId, branchId, amount,
                currency, paymentMethod, idempotencyKey, reversesEventId,
                sourceDeviceId, actorUserId);
    }

    private PaymentEventResult record(String eventType, UUID syncId, long orderId,
            long tenantId, long branchId, BigDecimal amount, String currency,
            String paymentMethod, String idempotencyKey, UUID reversesEventId,
            UUID sourceDeviceId, Long actorUserId) {
        List<PaymentEventResult> existing = findByIdempotency(tenantId, idempotencyKey);
        if (!existing.isEmpty()) {
            PaymentEventResult prior = existing.getFirst();
            requireSameRequest(prior, eventType, syncId, orderId, branchId, amount, currency,
                    paymentMethod, reversesEventId);
            return prior.asReplay();
        }

        int inserted;
        try {
            String onConflict = isPostgres()
                    ? " ON CONFLICT (tenant_id, idempotency_key) DO NOTHING"
                    : "";
            inserted = jdbc.update("""
                    INSERT INTO payment_events
                      (sync_id, order_id, tenant_id, branch_id, event_type, amount,
                       currency, payment_method, idempotency_key, reverses_event_id,
                       source_device_id, actor_user_id)
                    SELECT ?, o.id, o.tenant_id, o.restaurant_id, ?, ?, ?, ?, ?, ?, ?, ?
                    FROM orders o
                    WHERE o.id = ? AND o.tenant_id = ? AND o.restaurant_id = ?
                    """ + onConflict, syncId, eventType, amount, currency, paymentMethod,
                    idempotencyKey, reversesEventId, sourceDeviceId, actorUserId,
                    orderId, tenantId, branchId);
        } catch (DuplicateKeyException exception) {
            // A repeated event UUID with a different idempotency key is also a
            // conflict; no existing financial event is overwritten.
            throw new IdempotencyConflictException("Payment event identity already exists", exception);
        }
        if (inserted == 0) {
            List<PaymentEventResult> replay = findByIdempotency(tenantId, idempotencyKey);
            if (replay.isEmpty()) {
                throw new IllegalArgumentException("Order does not belong to the supplied tenant and branch");
            }
            PaymentEventResult prior = replay.getFirst();
            requireSameRequest(prior, eventType, syncId, orderId, branchId, amount, currency,
                    paymentMethod, reversesEventId);
            return prior.asReplay();
        }

        PaymentEventResult stored = findBySyncId(syncId);
        if (stored == null) {
            throw new IllegalStateException("Inserted payment event could not be read back");
        }
        jdbc.update("""
                INSERT INTO ops.sync_identity_map
                  (entity_type, sync_id, cloud_id, mapping_status)
                VALUES ('payments', ?, ?, 'CLOUD_ONLY')
                """, syncId, stored.databaseId());
        return stored;
    }

    private List<PaymentEventResult> findByIdempotency(long tenantId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, sync_id, order_id, branch_id, event_type, amount, currency,
                       payment_method, idempotency_key, reverses_event_id
                FROM payment_events WHERE tenant_id = ? AND idempotency_key = ?
                """, (rs, row) -> new PaymentEventResult(
                rs.getLong("id"), rs.getObject("sync_id", UUID.class),
                rs.getLong("order_id"), rs.getLong("branch_id"), rs.getString("event_type"),
                rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("payment_method"), rs.getString("idempotency_key"),
                rs.getObject("reverses_event_id", UUID.class), false),
                tenantId, idempotencyKey);
    }

    private boolean isPostgres() {
        return jdbc.execute((ConnectionCallback<Boolean>) connection -> connection.getMetaData().getDatabaseProductName()
                .toLowerCase(java.util.Locale.ROOT).contains("postgresql"));
    }

    private PaymentEventResult findBySyncId(UUID syncId) {
        List<PaymentEventResult> rows = jdbc.query("""
                SELECT id, sync_id, order_id, branch_id, event_type, amount, currency,
                       payment_method, idempotency_key, reverses_event_id
                FROM payment_events WHERE sync_id = ?
                """, (rs, row) -> new PaymentEventResult(
                rs.getLong("id"), rs.getObject("sync_id", UUID.class),
                rs.getLong("order_id"), rs.getLong("branch_id"), rs.getString("event_type"),
                rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("payment_method"), rs.getString("idempotency_key"),
                rs.getObject("reverses_event_id", UUID.class), false), syncId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void requireSameRequest(PaymentEventResult prior, String eventType,
            UUID syncId, long orderId, long branchId, BigDecimal amount, String currency,
            String paymentMethod, UUID reversesEventId) {
        if (!prior.syncId().equals(syncId) || prior.orderId() != orderId
                || prior.branchId() != branchId
                || !prior.eventType().equals(eventType)
                || prior.amount().compareTo(amount) != 0
                || !prior.currency().equals(currency)
                || !Objects.equals(prior.paymentMethod(), paymentMethod)
                || !Objects.equals(prior.reversesEventId(), reversesEventId)) {
            throw new IdempotencyConflictException(
                    "Idempotency key was reused for a different payment request");
        }
    }

    private static void validate(UUID syncId, BigDecimal amount, String currency,
            String paymentMethod, String idempotencyKey, boolean paymentMethodRequired) {
        if (syncId == null || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment event requires a UUID and positive amount");
        }
        try {
            if (amount.setScale(2, RoundingMode.UNNECESSARY).precision() > 12) {
                throw new IllegalArgumentException("Payment amount exceeds NUMERIC(12,2)");
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Payment amount supports at most two decimal places", exception);
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be a three-letter uppercase code");
        }
        if (paymentMethodRequired && (paymentMethod == null || paymentMethod.isBlank()
                || paymentMethod.length() > 50)) {
            throw new IllegalArgumentException("Payment method is required and must be at most 50 characters");
        }
        if (paymentMethod != null && paymentMethod.length() > 50) {
            throw new IllegalArgumentException("Payment method must be at most 50 characters");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new IllegalArgumentException("A payment idempotency key is required");
        }
    }

    public record PaymentEventResult(long databaseId, UUID syncId, long orderId, long branchId,
            String eventType, BigDecimal amount, String currency, String paymentMethod,
            String idempotencyKey, UUID reversesEventId, boolean replay) {
        PaymentEventResult asReplay() {
            return new PaymentEventResult(databaseId, syncId, orderId, branchId, eventType, amount,
                    currency, paymentMethod, idempotencyKey, reversesEventId, true);
        }
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }

        public IdempotencyConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
