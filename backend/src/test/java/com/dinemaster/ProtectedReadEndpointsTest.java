package com.dinemaster;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProtectedReadEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/me",
            "/api/v1/auth/me/permissions",
            "/api/v1/auth/devices",
            "/api/v1/users",
            "/api/v1/admin/staff",
            "/api/v1/users/1",
            "/api/v1/users/1/sessions",
            "/api/v1/users/1/audit",
            "/api/v1/categories",
            "/api/v1/categories/1",
            "/api/v1/products",
            "/api/v1/products/all",
            "/api/v1/products/1",
            "/api/v1/products/category/starters",
            "/api/v1/menu",
            "/api/v1/branches/1/availability",
            "/api/v1/customers",
            "/api/v1/customers/1",
            "/api/v1/reservations",
            "/api/v1/branches",
            "/api/v1/branches/1",
            "/api/v1/branches/1/hours",
            "/api/v1/floors",
            "/api/v1/tables",
            "/api/v1/tables/1",
            "/api/v1/orders",
            "/api/v1/orders/parked",
            "/api/v1/orders/1",
            "/api/v1/orders/1/items",
            "/api/v1/orders/1/timeline",
            "/api/v1/orders/1/bill",
            "/api/v1/inventory",
            "/api/v1/recipes",
            "/api/v1/recipes/1",
            "/api/v1/recipes/1/cost",
            "/api/v1/kitchen-tickets",
            "/api/v1/kitchen-tickets/history",
            "/api/v1/kitchen-tickets/events",
            "/api/v1/kitchen-tickets/1/delays",
            "/api/v1/kitchen-tickets/order/1",
            "/api/v1/kots/1",
            "/api/v1/orders/1/kots",
            "/api/v1/print-jobs/failed",
            "/api/v1/guest-service-requests",
            "/api/v1/roles",
            "/api/v1/roles/permissions",
            "/api/v1/roles/OWNER",
            "/api/v1/tenants",
            "/api/v1/super-admin/tenants",
            "/api/v1/dashboard/summary",
            "/api/v1/dashboard/revenue-trend"
    })
    void protectedReadRouteRejectsAnonymousRequest(String uri) throws Exception {
        MockHttpServletRequestBuilder request = get(uri)
                .with(anonymous())
                .param("restaurantId", "1")
                .param("branchId", "1")
                .param("productId", "1")
                .param("afterSeq", "0")
                .param("limit", "200")
                .param("sort", "oldest")
                .param("days", "7");

        mockMvc.perform(request).andExpect(status().isUnauthorized());
    }
}
