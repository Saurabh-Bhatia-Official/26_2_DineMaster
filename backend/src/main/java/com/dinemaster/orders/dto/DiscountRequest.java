package com.dinemaster.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

public record DiscountRequest(@NotBlank String type, @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal value,
        @NotBlank @Size(max = 500) String remark, String managerUsername, String managerPin) { }
