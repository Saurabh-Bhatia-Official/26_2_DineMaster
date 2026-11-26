package com.dinemaster.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class EdgeOperationalWriteGuardTest {
    @Test
    void blocksOperationalMutationWhenEdgeAuthorityIsEnabled() throws Exception {
        var filter = new EdgeOperationalWriteGuard(true);
        var request = new MockHttpServletRequest("POST", "/api/v1/orders/42/payment");
        var response = new MockHttpServletResponse();
        var chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertFalse(chainCalled.get());
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertNotNull(response.getHeader("X-Request-Id"));
        assertTrue(response.getContentAsString().contains("EDGE_REQUIRED"));
    }

    @Test
    void leavesOperationalMutationsAloneWhenPrototypeModeIsDisabled() throws Exception {
        var filter = new EdgeOperationalWriteGuard(false);
        var request = new MockHttpServletRequest("POST", "/api/v1/orders");
        var response = new MockHttpServletResponse();
        var chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertTrue(chainCalled.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void doesNotBlockReadsOrCloudAdministrationRoutes() throws Exception {
        var filter = new EdgeOperationalWriteGuard(true);
        for (var route : new String[][] {
                {"GET", "/api/v1/orders"},
                {"PUT", "/api/v1/products/7"},
                {"POST", "/api/v1/branches"},
                {"POST", "/api/v1/ordersExtra"}
        }) {
            var request = new MockHttpServletRequest(route[0], route[1]);
            var response = new MockHttpServletResponse();
            var chainCalled = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

            assertTrue(chainCalled.get(), route[0] + " " + route[1]);
        }
    }
}
