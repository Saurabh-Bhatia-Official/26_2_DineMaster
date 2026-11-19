package com.dinemaster.catalog.service;

import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.orders.repository.OrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;

@Service
public class ProductService {
    private final ProductRepository products;
    private final OrderItemRepository orderItems;
    public ProductService(ProductRepository products, OrderItemRepository orderItems) {
        this.products = products; this.orderItems = orderItems;
    }
    public boolean skuTaken(Long tenantId, String sku, Long exceptProductId) {
        if (sku == null || sku.isBlank()) return false;
        return exceptProductId == null
                ? products.existsByTenantIdAndSkuIgnoreCaseAndDeletedAtIsNull(tenantId, sku.trim())
                : products.existsByTenantIdAndSkuIgnoreCaseAndIdNotAndDeletedAtIsNull(tenantId, sku.trim(), exceptProductId);
    }
    @Transactional
    public Product setAvailability(Product product, boolean available) {
        product.setAvailable(available);
        return products.save(product);
    }
    @Transactional
    public Product archive(Product product) {
        product.setAvailable(false);
        product.setDeletedAt(OffsetDateTime.now());
        return products.save(product);
    }
    @Transactional
    public boolean deleteOrArchive(Product product) {
        if (orderItems.existsByProduct_Id(product.getId())) {
            archive(product);
            return true;
        }
        products.delete(product);
        return false;
    }
}
