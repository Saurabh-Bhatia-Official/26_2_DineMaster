package com.dinemaster;


import com.dinemaster.catalog.controller.ProductController;
import com.dinemaster.cache.CatalogCacheService;
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

import static org.assertj.core.api.Assertions.assertThat;
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
    @Autowired
    private CatalogCacheService cachedReads;

    private Long restaurantId;
    private Long productId;
    private Long tenantId;

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
        tenantId = tenant.getId();
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
        category = categoryRepository.saveAndFlush(category);

        Product product = new Product();
        product.setName("Egg Roll");
        product.setPrice(new BigDecimal("120.00"));
        product.setCategory("Starters");
        product.setVeg(2);
        product.setTenantId(tenant.getId());
        product.setRestaurant(restaurant);
        product.setCategoryRef(category);
        var taxSlab = new com.dinemaster.catalog.entity.TaxSlab();
        taxSlab.setName("Test tax");
        taxSlab.setTenantId(tenant.getId());
        taxSlab.setRatePercent(BigDecimal.ZERO);
        product.setTaxSlab(taxSlabRepository.saveAndFlush(taxSlab));
        productId = productRepository.saveAndFlush(product).getId();
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

    @Test
    @WithMockUser(username = "product-owner", authorities = {"menu:view", "menu:manage"})
    void productUpdateInvalidatesCachedBranchMenu() throws Exception {
        mockMvc.perform(get("/api/v1/menu").param("branchId", restaurantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].products[0].name").value("Egg Roll"))
                .andExpect(jsonPath("$.categories[0].products[0].price").value(120.00));
        mockMvc.perform(get("/api/v1/branches/{branchId}/availability", restaurantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Egg Roll"));
        assertThat(cachedReads.guestMenu(tenantId, restaurantId).items().getFirst().name())
                .isEqualTo("Egg Roll");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Egg Roll Updated",
                                  "price": 120.00,
                                  "category": "Starters",
                                  "is_veg": 2,
                                  "restaurantId": %d,
                                  "gstPercentage": 0.00
                                }
                                """.formatted(restaurantId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/menu").param("branchId", restaurantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].products[0].name").value("Egg Roll Updated"))
                .andExpect(jsonPath("$.categories[0].products[0].price").value(120.00));
        mockMvc.perform(get("/api/v1/branches/{branchId}/availability", restaurantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Egg Roll Updated"));
        assertThat(cachedReads.guestMenu(tenantId, restaurantId).items().getFirst().name())
                .isEqualTo("Egg Roll Updated");
    }
}
