package com.dinemaster;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void guestRoutesRejectExpiredOrMalformedSessionTokens() throws Exception {
        mockMvc.perform(get("/guest/menu").param("t", "short-token"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RESOURCE_EXPIRED"));

        mockMvc.perform(get("/guest/orders/{id}", 1).param("t", "short-token"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RESOURCE_EXPIRED"));

        mockMvc.perform(post("/guest/call-waiter").param("t", "short-token"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RESOURCE_EXPIRED"));

        mockMvc.perform(post("/guest/orders")
                        .param("t", "short-token")
                        .header("Idempotency-Key", "guest-test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":1,"quantity":1}]}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RESOURCE_EXPIRED"));
    }
}
