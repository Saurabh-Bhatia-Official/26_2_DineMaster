package com.dinemaster;

import com.dinemaster.diningtable.entity.DiningTable;
import com.dinemaster.diningtable.repository.DiningTableRepository;
import com.dinemaster.diningtable.repository.FloorRepository;
import com.dinemaster.restaurant.entity.Restaurant;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import com.dinemaster.tenant.Tenant;
import com.dinemaster.tenant.TenantRepository;
import com.dinemaster.user.entity.User;
import com.dinemaster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TableControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DiningTableRepository tables;

    @Autowired
    private FloorRepository floors;

    @Autowired
    private RestaurantRepository branches;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private UserRepository users;

    private Long branchId;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Table API test tenant " + System.nanoTime()));
        User owner = users.findByUsername("table-owner").orElseGet(User::new);
        owner.setName("Table API Owner");
        owner.setUsername("table-owner");
        owner.setPassword("test");
        owner.setRole("OWNER");
        owner.setTenantId(tenant.getId());
        users.saveAndFlush(owner);

        Restaurant branch = new Restaurant();
        branch.setName("Table API branch");
        branch.setCode("TABLE");
        branch.setTenantId(tenant.getId());
        branchId = branches.saveAndFlush(branch).getId();
    }

    @Test
    @WithMockUser(username = "table-owner", authorities = {
            "table:view", "table:manage_layout", "table:update_status"
    })
    void tableWorkflowValidatesLayoutAndStatusTransitionsAndDeactivates() throws Exception {
        mockMvc.perform(post("/api/v1/tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tableJson("T-1", branchId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tableNumber").value("T-1"))
                .andExpect(jsonPath("$.statusCode").value("AVAILABLE"));

        DiningTable table = tables.findByRestaurant_IdOrderByTableNumberAsc(branchId).getFirst();
        mockMvc.perform(post("/api/v1/tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tableJson("T-1", branchId)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/tables").param("branchId", branchId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(table.getId()));

        mockMvc.perform(put("/api/v1/tables/{id}", table.getId())
                        .param("version", "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tableJson("T-1", branchId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Table layout is stale"));

        mockMvc.perform(put("/api/v1/tables/{id}/status", table.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"OCCUPIED","version":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value("OCCUPIED"));

        mockMvc.perform(put("/api/v1/tables/{id}/status", table.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"AVAILABLE","version":2}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Illegal table status transition"));

        mockMvc.perform(put("/api/v1/tables/{id}/status", table.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CLEANING","version":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value("CLEANING"));

        mockMvc.perform(delete("/api/v1/tables/{id}", table.getId()))
                .andExpect(status().isNoContent());
        assertThat(tables.findById(table.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    @WithMockUser(username = "table-owner", authorities = {"table:manage_layout"})
    void tableCreationRejectsInvalidCapacity() throws Exception {
        mockMvc.perform(post("/api/v1/tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tableNumber":"T-2","capacity":0,"section":"Main","tableType":"Standard","restaurantId":%d}
                                """.formatted(branchId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private static String tableJson(String number, Long restaurantId) {
        return """
                {"tableNumber":"%s","name":"Window table","capacity":4,"section":"Main","tableType":"Standard","restaurantId":%d}
                """.formatted(number, restaurantId);
    }
}
