package com.dinemaster.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OrderReasonRequest(@NotBlank @Size(max = 1000) String reason) { }
