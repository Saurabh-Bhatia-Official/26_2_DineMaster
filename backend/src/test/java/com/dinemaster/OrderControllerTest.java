package com.dinemaster;


import com.dinemaster.catalog.entity.Category;
import com.dinemaster.catalog.entity.Product;
import com.dinemaster.catalog.repository.ProductRepository;
import com.dinemaster.orders.controller.OrderController;
import com.dinemaster.orders.entity.Order;
import com.dinemaster.orders.entity.OrderItem;
import com.dinemaster.orders.repository.OrderItemRepository;
import com.dinemaster.orders.repository.OrderRepository;
import com.dinemaster.restaurant.entity.Restaurant;
import com.dinemaster.restaurant.repository.RestaurantRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private OrderRepository orderRepository;

        @Autowired
        private OrderItemRepository orderItemRepository;

        @Autowired
        private ProductRepository productRepository;

        @Autowired
        private RestaurantRepository restaurantRepository;

        @Autowired
        private TenantRepository tenantRepository;

        @Autowired
        private com.dinemaster.user.repository.UserRepository userRepository;

        @Autowired
        private com.dinemaster.catalog.repository.TaxSlabRepository taxSlabRepository;

        private Long restaurantId;
        private Long orderId;

        @BeforeEach
        void setUp() {
                orderItemRepository.deleteAllInBatch();
                orderRepository.deleteAllInBatch();
                productRepository.deleteAllInBatch();
                restaurantRepository.deleteAllInBatch();
                userRepository.deleteAllInBatch();
                tenantRepository.deleteAllInBatch();

                Tenant tenant = tenantRepository.saveAndFlush(new Tenant("Order test tenant"));
                User owner = new User();
                owner.setName("Order Test Owner");
                owner.setUsername("order-owner");
                owner.setPassword("test");
                owner.setRole("OWNER");
                owner.setTenantId(tenant.getId());
                userRepository.saveAndFlush(owner);

                Restaurant restaurant = new Restaurant();
                restaurant.setName("Order API Test Restaurant");
                restaurant.setCode("ORDER-BRANCH");
                restaurant.setTenantId(tenant.getId());
                restaurantId = restaurantRepository.saveAndFlush(restaurant).getId();

                Product product = new Product();
                product.setName("Test Pasta");
                product.setPrice(new BigDecimal("12.50"));
                product.setCategory("Main Course");
                product.setVeg(1);
                product.setTenantId(tenant.getId());
                product.setRestaurant(restaurant);
                com.dinemaster.catalog.entity.TaxSlab taxSlab = new com.dinemaster.catalog.entity.TaxSlab();
                taxSlab.setName("Test tax");
                taxSlab.setTenantId(tenant.getId());
                taxSlab.setRatePercent(BigDecimal.ZERO);
                product.setTaxSlab(taxSlabRepository.saveAndFlush(taxSlab));
                Product savedProduct = productRepository.saveAndFlush(product);

                Order order = new Order();
                order.setOrderNo("ORDER-TEST-1");
                order.setRestaurant(restaurant);
                order.setStatus("DRAFT");
                order.setType("Dine In");
                order.setTotalAmount(new BigDecimal("25.00"));
                order.setDiscountAmount(BigDecimal.ZERO);
                Order savedOrder = orderRepository.saveAndFlush(order);
                orderId = savedOrder.getId();

                OrderItem item = new OrderItem();
                item.setOrder(savedOrder);
                item.setProduct(savedProduct);
                item.setProductNameSnapshot(savedProduct.getName());
                item.setQuantity(2);
                item.setPrice(savedProduct.getPrice());
                item.setStatus("Pending");
                orderItemRepository.saveAndFlush(item);
        }

        @Test
        @WithMockUser(username = "order-owner", authorities = {"order:view"})
        void orderEndpointsSerializeLazyRelationshipsWithoutServerError() throws Exception {
                mockMvc.perform(get("/api/v1/orders")
                                .param("restaurantId", restaurantId.toString())
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].id").value(orderId))
                                .andExpect(jsonPath("$[0].restaurantId").value(restaurantId));

                mockMvc.perform(get("/api/v1/orders/{id}", orderId)
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(orderId))
                                .andExpect(jsonPath("$.totalAmount").value(25.0));

                mockMvc.perform(get("/api/v1/orders/{id}/items", orderId)
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].product_name").value("Test Pasta"))
                                .andExpect(jsonPath("$[0].quantity").value(2));
        }

        @Test
        @WithMockUser
        void missingOrderAndRestaurantReturnNotFound() throws Exception {
                mockMvc.perform(get("/api/orders")
                                .param("restaurantId", "999999")
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNotFound());

                mockMvc.perform(get("/api/orders/{id}", 999999)
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNotFound());

                mockMvc.perform(get("/api/orders/{id}/items", 999999)
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(username = "order-owner", authorities = {"order:park", "order:update", "payment:record", "order:cancel"})
        void holdingDraftOrderWorksAndPrematurePaymentConflicts() throws Exception {
                mockMvc.perform(post("/api/v1/orders/{id}/hold", orderId))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("DRAFT"))
                                .andExpect(jsonPath("$.parked").value(true));

                mockMvc.perform(post("/api/v1/orders/{id}/payment", orderId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"paymentMethod\":\"UPI\"}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("CONFLICT"));
        }
}
