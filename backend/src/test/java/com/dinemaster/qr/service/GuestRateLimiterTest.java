package com.dinemaster.qr.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuestRateLimiterTest {

    @Test
    void appliesGuestRequestLimitPerIpAddress() {
        GuestRateLimiter limiter = new GuestRateLimiter();

        for (int attempt = 0; attempt < 30; attempt++) {
            assertThat(limiter.allow("192.0.2.1")).isTrue();
        }

        assertThat(limiter.allow("192.0.2.1")).isFalse();
        assertThat(limiter.allow("192.0.2.2")).isTrue();
    }
}
