package com.dinemaster;

import com.dinemaster.customer.entity.Customer;
import com.dinemaster.customer.repository.CustomerRepository;
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
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private UserRepository users;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        customers.deleteAllInBatch();
        users.deleteAllInBatch();
        tenants.deleteAllInBatch();

        Tenant tenant = tenants.saveAndFlush(new Tenant("Customer API test tenant"));
        tenantId = tenant.getId();
        User owner = new User();
        owner.setName("Customer API Owner");
        owner.setUsername("customer-owner");
        owner.setPassword("test");
        owner.setRole("OWNER");
        owner.setTenantId(tenantId);
        users.saveAndFlush(owner);
    }

    @Test
    @WithMockUser(username = "customer-owner", authorities = {"customer:view", "customer:manage"})
    void customerCrudPersistsTenantScopeAndNormalizesFields() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  Casey Customer  ","phone":" 555-0101 ","email":"casey@example.test"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Casey Customer"))
                .andExpect(jsonPath("$.phone").value("555-0101"))
                .andExpect(jsonPath("$.email").value("casey@example.test"))
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andExpect(jsonPath("$.loyaltyPoints").value(0));

        Customer customer = customers.findByPhoneAndTenantId("555-0101", tenantId).orElseThrow();
        mockMvc.perform(get("/api/v1/customers/{id}", customer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customer.getId()));

        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].phone").value("555-0101"));

        mockMvc.perform(put("/api/v1/customers/{id}", customer.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Casey Updated","phone":"555-0102","email":"updated@example.test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Casey Updated"))
                .andExpect(jsonPath("$.phone").value("555-0102"));

        mockMvc.perform(delete("/api/v1/customers/{id}", customer.getId()))
                .andExpect(status().isNoContent());
        assertThat(customers.findById(customer.getId())).isEmpty();
    }

    @Test
    @WithMockUser(username = "customer-owner", authorities = {"customer:manage"})
    void duplicatePhoneAndInvalidEmailAreRejected() throws Exception {
        Customer customer = new Customer();
        customer.setName("Existing customer");
        customer.setPhone("555-0101");
        customer.setTenantId(tenantId);
        customers.saveAndFlush(customer);

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Duplicate","phone":"555-0101"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Phone number already exists"));

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Invalid Email","phone":"555-0102","email":"not-an-email"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
