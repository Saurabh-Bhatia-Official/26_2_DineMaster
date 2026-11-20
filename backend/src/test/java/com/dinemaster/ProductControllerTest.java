package com.dinemaster;


import com.dinemaster.catalog.controller.ProductController;
import com.dinemaster.catalog.entity.Category;
import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.repository.CategoryRepository;
import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.restaurant.entity.Restaurant;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.entity.OrderItem;
import com.dinemaster.orders.repository.OrderRepository;
import com.dinemaster.orders.repository.OrderItemRepository;
import com.dinemaster.user.entity.User;
import com.dinemaster.tenant.Tenant;
import com.dinemaster.tenant.TenantRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private com.dinemaster.catalog.repository.TaxSlabRepository taxSlabRepository;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private com.dinemaster.user.repository.UserRepository userRepository;

    private Long restaurantId;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        categoryRepository.deleteAllInBatch();
        restaurantRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        tenantRepository.deleteAllInBatch();

        Tenant tenant = tenantRepository.saveAndFlush(new Tenant("Product test tenant"));
        User owner = new User();
        owner.setName("Product Test Owner");
        owner.setUsername("product-owner");
        owner.setPassword("test");
        owner.setRole("OWNER");
        owner.setTenantId(tenant.getId());
        userRepository.saveAndFlush(owner);

        Restaurant restaurant = new Restaurant();
        restaurant.setName("Product Test Branch");
        restaurant.setCode("PRODUCT-BRANCH");
        restaurant.setTenantId(tenant.getId());
        restaurantId = restaurantRepository.saveAndFlush(restaurant).getId();

        Category category = new Category();
        category.setName("Starters");
        category.setTenantId(tenant.getId());
        categoryRepository.saveAndFlush(category);

        Product product = new Product();
        product.setName("Egg Roll");
        product.setPrice(new BigDecimal("120.00"));
        product.setCategory("Starters");
        product.setVeg(2);
        product.setTenantId(tenant.getId());
        product.setRestaurant(restaurant);
        var taxSlab = new com.dinemaster.catalog.entity.TaxSlab();
        taxSlab.setName("Test tax");
        taxSlab.setTenantId(tenant.getId());
        taxSlab.setRatePercent(BigDecimal.ZERO);
        product.setTaxSlab(taxSlabRepository.saveAndFlush(taxSlab));
        productRepository.saveAndFlush(product);
    }

    @Test
    @WithMockUser(username = "product-owner", authorities = {"menu:view"})
    void productResponsePreservesFoodTypeAndAvailabilityContract() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("restaurantId", restaurantId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].is_veg").value(2))
                .andExpect(jsonPath("$[0].is_available").value(true));
    }

    @Test
    @WithMockUser(username = "product-owner", authorities = {"menu:manage"})
    void productRejectsUnsupportedFoodType() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "name": "Invalid Food Type",
                          "price": 100.00,
                          "category": "Starters",
                          "is_veg": 4,
                          "restaurantId": %d
                        }
                        """.formatted(restaurantId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.veg").value("is_veg must be between 0 and 3"));
    }

    @Test
    @WithMockUser(username = "product-owner", authorities = {"menu:view"})
    void missingProductIsDeniedByBranchScopeInterceptor() throws Exception {
        mockMvc.perform(get("/api/v1/products/{id}", 999999))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    @WithMockUser(username = "product-owner")
    void productReadRequiresMenuPermission() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("restaurantId", restaurantId.toString()))
                .andExpect(status().isForbidden());
    }
}
