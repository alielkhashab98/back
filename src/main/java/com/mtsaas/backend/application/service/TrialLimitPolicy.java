package com.mtsaas.backend.application.service;

public final class TrialLimitPolicy {
    public static final int GUEST_FREE_CONVERSIONS = 5;
    public static final int SIGNED_IN_FREE_CREDITS = 10;

    private TrialLimitPolicy() {
    }
}
