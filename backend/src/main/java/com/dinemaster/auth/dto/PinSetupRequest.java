package com.dinemaster.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PinSetupRequest(@NotBlank String currentPassword,
        @NotBlank @Pattern(regexp = "[0-9]{6}", message = "PIN must contain exactly six digits") String pin) { }
