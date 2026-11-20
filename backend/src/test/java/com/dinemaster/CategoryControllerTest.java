package com.dinemaster;

import com.dinemaster.catalog.entity.Category;
import com.dinemaster.catalog.repository.CategoryRepository;
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
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private UserRepository users;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        categories.deleteAllInBatch();
        users.deleteAllInBatch();
        tenants.deleteAllInBatch();

        Tenant tenant = tenants.saveAndFlush(new Tenant("Category API test tenant"));
        tenantId = tenant.getId();
        User owner = new User();
        owner.setName("Category API Owner");
        owner.setUsername("category-owner");
        owner.setPassword("test");
        owner.setRole("OWNER");
        owner.setTenantId(tenantId);
        users.saveAndFlush(owner);
    }

    @Test
    @WithMockUser(username = "category-owner", authorities = {"menu:view", "menu:manage"})
    void categoryCrudSupportsReorderingAndRejectsDuplicateNames() throws Exception {
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  Desserts ","sortOrder":10,"active":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Desserts"))
                .andExpect(jsonPath("$.tenantId").value(tenantId));

        Category category = categories.findFirstByNameIgnoreCaseAndTenantId("desserts", tenantId).orElseThrow();
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"DESSERTS"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Category already exists"));

        mockMvc.perform(put("/api/v1/categories/{id}", category.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sweets","sortOrder":4}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sweets"));

        mockMvc.perform(put("/api/v1/categories/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"id":%d,"sortOrder":2}]}
                                """.formatted(category.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sortOrder").value(2));

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Sweets"));

        mockMvc.perform(delete("/api/v1/categories/{id}", category.getId()))
                .andExpect(status().isNoContent());
        assertThat(categories.findById(category.getId())).isEmpty();
    }

    @Test
    @WithMockUser(username = "category-owner", authorities = {"menu:manage"})
    void categoryCreateRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" "}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
