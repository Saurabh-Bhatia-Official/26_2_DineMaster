package com.dinemaster.orders.service;

import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.entity.OrderItem;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TotalsCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public void recalculate(Order order, List<OrderItem> items) {
        BigDecimal subtotal = items.stream().filter(i -> !"CANCELLED".equalsIgnoreCase(i.getStatus()))
                .map(i -> i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())).setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = "PERCENT".equalsIgnoreCase(order.getDiscountType())
                ? subtotal.multiply(order.getDiscountValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                : order.getDiscountValue();
        if (discount.signum() < 0 || discount.signum() > 0 && discount.compareTo(subtotal) >= 0)
            throw new IllegalArgumentException("Discount must be below the eligible subtotal");
        BigDecimal tax = BigDecimal.ZERO;
        if (subtotal.signum() > 0) {
            Map<BigDecimal, BigDecimal> taxableByRate = new HashMap<>();
            for (OrderItem item : items) {
                if ("CANCELLED".equalsIgnoreCase(item.getStatus())) continue;
                BigDecimal line = item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                BigDecimal taxable = line.subtract(discount.multiply(line.divide(subtotal, MathContext.DECIMAL128)));
                taxableByRate.merge(item.getTaxRateSnapshot(), taxable, BigDecimal::add);
            }
            tax = taxableByRate.entrySet().stream().map(e -> e.getValue().multiply(e.getKey())
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP)).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        BigDecimal raw = subtotal.subtract(discount).add(tax).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rounded = raw.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
        order.setSubtotal(subtotal);
        order.setDiscountAmount(discount.setScale(2, RoundingMode.HALF_UP));
        order.setTaxAmount(tax.setScale(2, RoundingMode.HALF_UP));
        order.setRoundOff(rounded.subtract(raw).setScale(2, RoundingMode.HALF_UP));
        order.setTotalAmount(rounded);
    }
}
