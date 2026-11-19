package com.dinemaster.orders.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.Set;

@Component
public class OrderStateMachine {
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("CONFIRMED", "CANCELLED"),
            "CONFIRMED", Set.of("BILLED", "CANCELLED", "VOIDED"),
            "BILLED", Set.of("CONFIRMED", "PAID"),
            "PAID", Set.of("COMPLETED", "VOIDED"));

    public void requireTransition(String from, String to) {
        String source = normalize(from), target = normalize(to);
        if (!TRANSITIONS.getOrDefault(source, Set.of()).contains(target))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "INVALID_TRANSITION: " + source + " -> " + target + " is not allowed");
    }

    private String normalize(String status) { return status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT); }
}
