package com.dinemaster;

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
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RestaurantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RestaurantRepository branches;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private UserRepository users;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Branch API test tenant " + System.nanoTime()));
        tenantId = tenant.getId();
        User owner = users.findByUsername("branch-owner").orElseGet(User::new);
        owner.setName("Branch API Owner");
        owner.setUsername("branch-owner");
        owner.setPassword("test");
        owner.setRole("OWNER");
        owner.setTenantId(tenantId);
        users.saveAndFlush(owner);
    }

    @Test
    @WithMockUser(username = "branch-owner", authorities = {
            "branch:view", "branch:create", "branch:update"
    })
    void branchCrudChecksTenantUniquenessAndOptimisticVersion() throws Exception {
        mockMvc.perform(post("/api/v1/branches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(branchJson("B-1", "Main branch", "Asia/Kolkata")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("B-1"))
                .andExpect(jsonPath("$.tenantId").value(tenantId));

        Restaurant branch = branches.findAllByTenantIdAndDeletedAtIsNullOrderById(tenantId).getFirst();
        mockMvc.perform(get("/api/v1/branches/{id}", branch.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Main branch"));
        mockMvc.perform(post("/api/v1/branches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(branchJson("b-1", "Duplicate", "Asia/Kolkata")))
                .andExpect(status().isConflict());

        mockMvc.perform(put("/api/v1/branches/{id}", branch.getId())
                        .header("If-Match", 99)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(branchJson("B-1", "Stale edit", "Asia/Kolkata")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Branch was changed by another request"));

        mockMvc.perform(put("/api/v1/branches/{id}", branch.getId())
                        .header("If-Match", branch.getVersion())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(branchJson("B-1", "Updated branch", "Asia/Kolkata")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated branch"));

        mockMvc.perform(get("/api/v1/branches/{id}", branch.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated branch"));

        mockMvc.perform(get("/api/v1/branches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Updated branch"));

        mockMvc.perform(post("/api/v1/branches/{id}/suspend", branch.getId()))
                .andExpect(status().isNoContent());
        assertThat(branches.findById(branch.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    @WithMockUser(username = "branch-owner", authorities = {"branch:create"})
    void branchCreateRejectsUnknownTimezone() throws Exception {
        mockMvc.perform(post("/api/v1/branches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(branchJson("B-2", "Invalid timezone", "Mars/Olympus")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Invalid IANA timezone"));
    }

    @Test
    @WithMockUser(username = "branch-owner", authorities = {"branch:create", "branch:view", "branch:update"})
    void branchHoursRequireACompleteUniqueWeekAndPersistNamedWeekdays() throws Exception {
        mockMvc.perform(post("/api/v1/branches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(branchJson("HOURS", "Hours branch", "Asia/Kolkata")))
                .andExpect(status().isCreated());
        Restaurant branch = branches.findAllByTenantIdAndDeletedAtIsNullOrderById(tenantId).getFirst();

        mockMvc.perform(put("/api/v1/branches/{id}/hours", branch.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"weekday":"MONDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                  {"weekday":"TUESDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                  {"weekday":"WEDNESDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                  {"weekday":"THURSDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                  {"weekday":"FRIDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                  {"weekday":"SATURDAY","openTime":null,"closeTime":null,"closed":true},
                                  {"weekday":"SUNDAY","openTime":null,"closeTime":null,"closed":true}
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[*].weekday", containsInAnyOrder(
                        "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")));

        mockMvc.perform(get("/api/v1/branches/{id}/hours", branch.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[*].weekday", containsInAnyOrder(
                        "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")));

        mockMvc.perform(put("/api/v1/branches/{id}/hours", branch.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"weekday":"MONDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                  {"weekday":"MONDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                  {"weekday":"WEDNESDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                  {"weekday":"THURSDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                  {"weekday":"FRIDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                  {"weekday":"SATURDAY","openTime":null,"closeTime":null,"closed":true},
                                  {"weekday":"SUNDAY","openTime":null,"closeTime":null,"closed":true}
                                ]
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("Weekdays must be unique"));

        mockMvc.perform(put("/api/v1/branches/{id}/hours", branch.getId())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                [
                                                  {"weekday":"MONDAY","openTime":"10:00:00","closeTime":"18:00:00","closed":false},
                                                  {"weekday":"TUESDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                                  {"weekday":"WEDNESDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                                  {"weekday":"THURSDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                                  {"weekday":"FRIDAY","openTime":"09:00:00","closeTime":"18:00:00","closed":false},
                                                  {"weekday":"SATURDAY","openTime":null,"closeTime":null,"closed":true},
                                                  {"weekday":"SUNDAY","openTime":null,"closeTime":null,"closed":true}
                                                ]
                                                """))
                                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/branches/{id}/hours", branch.getId()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[?(@.weekday == 'MONDAY')].openTime")
                                        .value(contains("10:00:00")));
    }

    private static String branchJson(String code, String name, String timezone) {
        return """
                {"code":"%s","name":"%s","timezone":"%s"}
                """.formatted(code, name, timezone);
    }
}
