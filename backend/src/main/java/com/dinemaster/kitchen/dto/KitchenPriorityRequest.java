package com.dinemaster.kitchen.dto;

import jakarta.validation.constraints.NotBlank;

public record KitchenPriorityRequest(@NotBlank String priority) { }
