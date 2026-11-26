package com.dinemaster.orders.service;

import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.entity.OrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TotalsCalculatorTest {

    private final TotalsCalculator calculator = new TotalsCalculator();

    @Test
    void recalculatesDiscountTaxAndWholeCurrencyRounding() {
        Order order = new Order();
        order.setDiscountType("FLAT");
        order.setDiscountValue(new BigDecimal("1.00"));

        calculator.recalculate(order, List.of(item("10.00", 2, "5.00")));

        assertThat(order.getSubtotal()).isEqualByComparingTo("20.00");
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("1.00");
        assertThat(order.getTaxAmount()).isEqualByComparingTo("0.95");
        assertThat(order.getRoundOff()).isEqualByComparingTo("0.05");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void ignoresCancelledItemsAndAppliesPercentageDiscount() {
        Order order = new Order();
        order.setDiscountType("PERCENT");
        order.setDiscountValue(new BigDecimal("10"));
        OrderItem cancelled = item("100.00", 1, "5.00");
        cancelled.setStatus("CANCELLED");

        calculator.recalculate(order, List.of(item("10.00", 2, "5.00"), cancelled));

        assertThat(order.getSubtotal()).isEqualByComparingTo("20.00");
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("2.00");
        assertThat(order.getTaxAmount()).isEqualByComparingTo("0.90");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("19.00");
    }

    @Test
    void rejectsNegativeOrSubtotalExhaustingDiscount() {
        Order order = new Order();
        order.setDiscountValue(new BigDecimal("10.00"));

        assertThatThrownBy(() -> calculator.recalculate(order, List.of(item("10.00", 1, "0.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Discount must be below the eligible subtotal");

        order.setDiscountValue(new BigDecimal("-0.01"));
        assertThatThrownBy(() -> calculator.recalculate(order, List.of(item("10.00", 1, "0.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OrderItem item(String price, int quantity, String taxRate) {
        OrderItem item = new OrderItem();
        item.setPrice(new BigDecimal(price));
        item.setQuantity(quantity);
        item.setTaxRateSnapshot(new BigDecimal(taxRate));
        return item;
    }
}
