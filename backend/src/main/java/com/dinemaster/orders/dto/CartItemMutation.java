package com.dinemaster.orders.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CartItemMutation(@NotNull @Positive Long productId, @NotNull @Positive Integer quantity,
        @Size(max = 2000) String notes) { }
