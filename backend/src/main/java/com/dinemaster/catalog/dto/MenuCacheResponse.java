package com.dinemaster.catalog.dto;

import java.math.BigDecimal;
import java.util.List;

public record MenuCacheResponse(
        List<Category> categories) {

    public record GuestMenu(List<GuestItem> items) { }

    public record BranchAvailability(List<AvailabilityItem> items) { }

    public record Category(Long id, String name, Integer sortOrder, List<MenuItem> products) { }

    public record MenuItem(Long id, String name, String description, BigDecimal price,
            BigDecimal taxRate, String imagePath) { }

    public record GuestItem(Long id, String name, String description, String category,
            BigDecimal price, String imagePath, Integer veg) { }

    public record AvailabilityItem(Long productId, String name, boolean isAvailable,
            BigDecimal price, BigDecimal priceOverride) { }
}
