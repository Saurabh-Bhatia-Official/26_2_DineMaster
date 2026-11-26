package com.dinemaster.recipe.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnitConversionServiceTest {

    private final UnitConversionService conversions = new UnitConversionService();

    @Test
    void convertsWithinSupportedMassAndVolumeFamilies() {
        assertThat(conversions.convert(new BigDecimal("1.5"), "kg", "g"))
                .isEqualByComparingTo("1500");
        assertThat(conversions.convert(new BigDecimal("2"), " Litre ", "ml"))
                .isEqualByComparingTo("2000");
    }

    @Test
    void returnsSameUnitQuantityAndRejectsUnsupportedOrIncompatibleUnits() {
        BigDecimal quantity = new BigDecimal("2.50");
        assertThat(conversions.convert(quantity, "g", " G ")).isSameAs(quantity);
        assertThatThrownBy(() -> conversions.convert(quantity, "g", "ml"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversions.convert(quantity, "cup", "ml"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
