package com.dinemaster.orders.dto;

import jakarta.validation.constraints.Positive;

public record AttachCustomerRequest(@Positive Long customerId) { }
