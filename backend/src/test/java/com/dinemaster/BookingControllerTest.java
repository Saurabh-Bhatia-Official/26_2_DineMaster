package com.dinemaster;

import com.dinemaster.booking.entity.Booking;
import com.dinemaster.booking.repository.BookingRepository;
import com.dinemaster.diningtable.entity.DiningTable;
import com.dinemaster.diningtable.repository.DiningTableRepository;
import com.dinemaster.diningtable.repository.FloorRepository;
import com.dinemaster.restaurant.entity.Restaurant;
import com.dinemaster.restaurant.repository.RestaurantRepository;
import com.dinemaster.tenant.Tenant;
import com.dinemaster.tenant.TenantRepository;
import com.dinemaster.user.entity.User;
import com.dinemaster.user.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingRepository bookings;

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
    private Long tableId;

    @BeforeEach
    void setUp() {
        Tenant tenant = tenants.saveAndFlush(new Tenant("Reservation API test tenant " + System.nanoTime()));
        User owner = users.findByUsername("reservation-owner").orElseGet(User::new);
        owner.setName("Reservation API Owner");
        owner.setUsername("reservation-owner");
        owner.setPassword("test");
        owner.setRole("OWNER");
        owner.setTenantId(tenant.getId());
        users.saveAndFlush(owner);

        Restaurant branch = new Restaurant();
        branch.setName("Reservation API branch");
        branch.setCode("RESERVE");
        branch.setTenantId(tenant.getId());
        branchId = branches.saveAndFlush(branch).getId();

        DiningTable table = new DiningTable();
        table.setTableNumber("R-" + UUID.randomUUID().toString().substring(0, 8));
        table.setCapacity(4);
        table.setRestaurant(branch);
        tableId = tables.saveAndFlush(table).getId();
    }

    @Test
    @WithMockUser(username = "reservation-owner", authorities = {
            "reservation:view", "reservation:create", "reservation:update"
    })
    void reservationWorkflowCoversCrudStatusAndInvalidReferences() throws Exception {
        String request = bookingJson(tableId, branchId, "Pending", 2);
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("Pending"))
                .andExpect(jsonPath("$.guestCount").value(2));

        Booking booking = bookings.findByRestaurant_IdOrderByBookingTimeAsc(branchId).getFirst();
        mockMvc.perform(get("/api/v1/reservations").param("restaurantId", branchId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(booking.getId()));

        mockMvc.perform(put("/api/v1/reservations/{id}", booking.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(tableId, branchId, "Confirmed", 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestCount").value(3))
                .andExpect(jsonPath("$.status").value("Confirmed"));

        mockMvc.perform(patch("/api/v1/reservations/{id}/status", booking.getId())
                        .param("value", "Cancelled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Cancelled"));

        mockMvc.perform(patch("/api/v1/reservations/{id}/status", booking.getId())
                        .param("value", "Unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid booking status"));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(999999L, branchId, "Pending", 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid restaurant, table, or customer"));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(tableId, branchId, "Pending", 0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(delete("/api/v1/reservations/{id}", booking.getId()))
                .andExpect(status().isNoContent());
        assertThat(bookings.findById(booking.getId())).isEmpty();
    }

    private static String bookingJson(Long tableId, Long branchId, String status, int guestCount) {
        return """
                {"tableId":%d,"customerName":"Test Guest","customerPhone":"555-1234","guestCount":%d,"bookingTime":"%s","status":"%s","restaurantId":%d}
                """.formatted(tableId, guestCount, OffsetDateTime.now().plusDays(1), status, branchId);
    }
}
