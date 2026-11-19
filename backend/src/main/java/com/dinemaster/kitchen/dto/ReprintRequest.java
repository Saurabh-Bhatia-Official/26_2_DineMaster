package com.dinemaster.kitchen.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ReprintRequest(@NotBlank @Size(max = 1000) String reason, Long printerId) { }
