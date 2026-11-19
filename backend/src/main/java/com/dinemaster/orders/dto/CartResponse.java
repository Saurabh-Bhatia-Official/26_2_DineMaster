package com.dinemaster.orders.dto;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(Long orderId, Long restaurantId, Long customerId, Long tableId, String type,
        String status, boolean parked, BigDecimal subtotal, BigDecimal discountAmount, String discountType,
        String discountRemark, BigDecimal taxAmount, BigDecimal roundOff, BigDecimal totalAmount,
        String orderNo, int version, List<OrderItemResponse> items) { }
