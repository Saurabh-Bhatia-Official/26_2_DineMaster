package com.dinemaster.orders.service;

public record BillRequestedEvent(Long branchId, Long orderId, Long tableId, Long requestedBy, String orderNo) { }
