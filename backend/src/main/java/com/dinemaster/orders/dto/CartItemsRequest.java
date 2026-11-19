package com.dinemaster.orders.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CartItemsRequest(@Valid @Size(max = 100) List<CartItemMutation> items) { }
