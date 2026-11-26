package com.dinemaster.kitchen.dto;

import jakarta.validation.constraints.NotBlank;

public record KitchenItemStatusRequest(@NotBlank String status, String reason) { }
