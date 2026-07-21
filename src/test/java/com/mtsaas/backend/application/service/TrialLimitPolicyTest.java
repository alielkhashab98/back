package com.mtsaas.backend.application.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrialLimitPolicyTest {

    @Test
    void shouldExposeTheExpectedTrialLimits() {
        assertEquals(5, TrialLimitPolicy.GUEST_FREE_CONVERSIONS);
        assertEquals(10, TrialLimitPolicy.SIGNED_IN_FREE_CREDITS);
    }
}
