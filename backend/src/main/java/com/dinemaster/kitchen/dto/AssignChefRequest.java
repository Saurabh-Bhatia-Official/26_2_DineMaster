package com.dinemaster.kitchen.dto;

import jakarta.validation.constraints.NotNull;

public record AssignChefRequest(@NotNull Long chefId) { }
