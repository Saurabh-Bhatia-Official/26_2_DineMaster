package com.dinemaster.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EdgeHostRefreshRequest(@NotBlank @Size(min = 40, max = 100) String refreshToken) { }
