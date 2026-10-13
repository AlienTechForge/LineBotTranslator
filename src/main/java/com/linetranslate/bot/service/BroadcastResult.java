package com.linetranslate.bot.service;

/**
 * Outcome of one administrator broadcast. {@code sent} counts pushes the LINE
 * Messaging API accepted, or pushes simulated while {@code testMode} is on;
 * {@code failed} counts pushes that threw. Every attempted user is one or the
 * other.
 */
public record BroadcastResult(int attempted, int sent, int failed, boolean testMode) {

    public BroadcastResult {
        if (attempted < 0 || sent < 0 || failed < 0) {
            throw new IllegalArgumentException("Broadcast counts must not be negative");
        }
        if (sent + failed != attempted) {
            throw new IllegalArgumentException("Broadcast sent and failed must add up to attempted");
        }
    }
}
