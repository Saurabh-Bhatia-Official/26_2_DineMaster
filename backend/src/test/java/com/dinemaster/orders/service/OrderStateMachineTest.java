package com.dinemaster.orders.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineTest {

    private final OrderStateMachine stateMachine = new OrderStateMachine();

    @Test
    void acceptsValidTransitionsWithoutCaseSensitivity() {
        stateMachine.requireTransition(" draft ", "confirmed");
        stateMachine.requireTransition("BILLED", "paid");
    }

    @Test
    void rejectsInvalidAndMissingTransitionsAsConflict() {
        assertThatThrownBy(() -> stateMachine.requireTransition("DRAFT", "PAID"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> stateMachine.requireTransition(null, "CONFIRMED"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
