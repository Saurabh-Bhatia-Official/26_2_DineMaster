package com.dinemaster.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    @Test
    void enforcesLimitPerClientKey() {
        LoginRateLimiter limiter = new LoginRateLimiter();

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(limiter.allow("client-a")).isTrue();
        }

        assertThat(limiter.allow("client-a")).isFalse();
        assertThat(limiter.allow("client-b")).isTrue();
    }
}
