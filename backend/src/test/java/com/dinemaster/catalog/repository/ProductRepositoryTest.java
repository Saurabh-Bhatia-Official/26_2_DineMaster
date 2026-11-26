package com.dinemaster.catalog.repository;

import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.entity.TaxSlab;
import com.dinemaster.restaurant.entity.Restaurant;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import com.dinemaster.tenant.Tenant;
import com.dinemaster.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ProductRepositoryTest {

    @Autowired
    private ProductRepository products;

    @Autowired
    private TaxSlabRepository taxSlabs;

    @Autowired
    private RestaurantRepository restaurants;

    @Autowired
    private TenantRepository tenants;

    @Test
    void persistsAndQueriesTenantProductsThenUpdatesAndDeletes() {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Repository test tenant"));
        Restaurant branch = new Restaurant();
        branch.setName("Repository test branch");
        branch.setCode("REPO-BRANCH");
        branch.setTenantId(tenant.getId());
        branch = restaurants.saveAndFlush(branch);

        TaxSlab taxSlab = new TaxSlab();
        taxSlab.setTenantId(tenant.getId());
        taxSlab.setName("No tax");
        taxSlab.setRatePercent(BigDecimal.ZERO);
        taxSlab = taxSlabs.saveAndFlush(taxSlab);

        Product product = new Product();
        product.setName("Zucchini soup");
        product.setTenantId(tenant.getId());
        product.setSku("SOUP-01");
        product.setPrice(new BigDecimal("8.50"));
        product.setCategory("Soup");
        product.setVeg(1);
        product.setTaxSlab(taxSlab);
        product.setRestaurant(branch);
        product = products.saveAndFlush(product);

        assertThat(products.existsByTenantIdAndSkuIgnoreCaseAndDeletedAtIsNull(tenant.getId(), "soup-01")).isTrue();
        assertThat(products.findByTenantIdAndDeletedAtIsNullOrderByNameAsc(tenant.getId()))
                .extracting(Product::getName).containsExactly("Zucchini soup");

        product.setAvailable(false);
        products.saveAndFlush(product);
        assertThat(products.findByIdAndTenantIdAndDeletedAtIsNull(product.getId(), tenant.getId()))
                .get().extracting(Product::isAvailable).isEqualTo(false);

        products.delete(product);
        assertThat(products.findById(product.getId())).isEmpty();
    }
}
