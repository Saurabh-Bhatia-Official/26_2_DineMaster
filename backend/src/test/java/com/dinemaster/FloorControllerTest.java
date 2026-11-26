package com.dinemaster;

import com.dinemaster.diningtable.entity.Floor;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FloorControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
        Tenant tenant = tenants.saveAndFlush(new Tenant("Floor API test tenant " + System.nanoTime()));
        User owner = users.findByUsername("floor-owner").orElseGet(User::new);
        owner.setName("Floor API Owner");
        owner.setUsername("floor-owner");
        owner.setPassword("test");
        owner.setRole("OWNER");
        owner.setTenantId(tenant.getId());
        users.saveAndFlush(owner);

        Restaurant branch = new Restaurant();
        branch.setName("Floor API branch");
        branch.setCode("FLOOR");
        branch.setTenantId(tenant.getId());
        branchId = branches.saveAndFlush(branch).getId();
    }

    @Test
    @WithMockUser(username = "floor-owner", authorities = {"table:view", "table:manage_layout"})
    void floorCrudRejectsDuplicateNamesAndListsInactiveFloorsOnlyWithLayoutPermission() throws Exception {
        mockMvc.perform(post("/api/v1/floors")
                        .param("branchId", branchId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(floorJson("  Ground Floor ", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ground Floor"))
                .andExpect(jsonPath("$.branchId").value(branchId));

        Floor floor = floors.findAllByBranchIdOrderByDisplayOrderAscNameAsc(branchId).getFirst();
        mockMvc.perform(post("/api/v1/floors")
                        .param("branchId", branchId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(floorJson("ground floor", true)))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/v1/floors/{id}", floor.getId())
                        .param("branchId", branchId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(floorJson("Ground Floor", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/v1/floors").param("branchId", branchId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get("/api/v1/floors")
                        .param("branchId", branchId.toString())
                        .param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Ground Floor"));
        assertThat(floors.findById(floor.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    @WithMockUser(username = "floor-owner", authorities = {"table:view"})
    void includingInactiveFloorsRequiresLayoutPermission() throws Exception {
        mockMvc.perform(get("/api/v1/floors")
                        .param("branchId", branchId.toString())
                        .param("includeInactive", "true"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Layout permission required"));
    }

    private static String floorJson(String name, boolean active) {
        return """
                {"name":"%s","description":"Dining area","displayOrder":1,"active":%s}
                """.formatted(name, active);
    }
}
