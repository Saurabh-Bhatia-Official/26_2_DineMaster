package com.dinemaster.recipe.service;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;
import java.util.Map;
@Service
public class UnitConversionService {
    private record Unit(String family, BigDecimal factor) { }
    private static final Map<String, Unit> UNITS = Map.ofEntries(
            Map.entry("G", new Unit("MASS", BigDecimal.ONE)), Map.entry("GRAM", new Unit("MASS", BigDecimal.ONE)),
            Map.entry("GRAMS", new Unit("MASS", BigDecimal.ONE)), Map.entry("KG", new Unit("MASS", new BigDecimal("1000"))),
            Map.entry("KILOGRAM", new Unit("MASS", new BigDecimal("1000"))), Map.entry("KILOGRAMS", new Unit("MASS", new BigDecimal("1000"))),
            Map.entry("ML", new Unit("VOLUME", BigDecimal.ONE)), Map.entry("L", new Unit("VOLUME", new BigDecimal("1000"))),
            Map.entry("LITRE", new Unit("VOLUME", new BigDecimal("1000"))), Map.entry("LITER", new Unit("VOLUME", new BigDecimal("1000"))));
    public BigDecimal convert(BigDecimal quantity, String from, String to) {
        String source = normalize(from), target = normalize(to);
        if (source.equals(target)) return quantity;
        Unit a = UNITS.get(source), b = UNITS.get(target);
        if (a == null || b == null || !a.family().equals(b.family()))
            throw new IllegalArgumentException("Cannot convert " + from + " to " + to + "; units must share a supported family");
        return quantity.multiply(a.factor()).divide(b.factor(), MathContext.DECIMAL128);
    }
    private String normalize(String unit) { return unit == null ? "" : unit.trim().toUpperCase(Locale.ROOT); }
}
