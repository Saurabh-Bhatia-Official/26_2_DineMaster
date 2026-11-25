package com.dinemaster.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Blocks branch operational mutations that bypass the registered Edge host
 * when Edge-authoritative mode is explicitly enabled.
 */
public final class EdgeOperationalWriteGuard extends OncePerRequestFilter {
    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> EDGE_OWNED_PREFIXES = Set.of(
            "/api/v1/orders",
            "/api/v1/kitchen-tickets",
            "/api/v1/tables",
            "/api/v1/reservations",
            "/api/v1/inventory",
            "/api/v1/customers");

    private final boolean enabled;

    public EdgeOperationalWriteGuard(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!enabled || !MUTATION_METHODS.contains(request.getMethod().toUpperCase())
                || !isEdgeOwnedOperationalPath(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String requestId = UUID.randomUUID().toString();
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Request-Id", requestId);
        response.getWriter().write("{\"error\":{\"code\":\"EDGE_REQUIRED\","
                + "\"message\":\"Branch operational writes require the authorized Edge host.\","
                + "\"requestId\":\"" + requestId + "\",\"retryable\":false}}");
    }

    private boolean isEdgeOwnedOperationalPath(String requestPath) {
        return EDGE_OWNED_PREFIXES.stream().anyMatch(prefix ->
                requestPath.equals(prefix) || requestPath.startsWith(prefix + "/"));
    }
}
