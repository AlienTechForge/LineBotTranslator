package com.linetranslate.bot.service;

/**
 * Outcome of one administrator broadcast. {@code sent} counts pushes the LINE
 * Messaging API accepted, or pushes simulated while {@code testMode} is on;
 * {@code failed} counts pushes that threw. {@code attempted} is every user the
 * broadcast reached, so it is always {@code sent + failed}.
 *
 * <p>{@code remaining} is non-zero only when the broadcasting thread was
 * interrupted (shutdown or cancellation) and the loop stopped early. It counts
 * the users that were not reached, including the one whose push was in flight
 * when the interrupt arrived: that push may still be delivered, but its outcome
 * is unknown. Those users are not retried.
 */
public record BroadcastResult(int attempted, int sent, int failed, boolean testMode, int remaining) {

    public BroadcastResult {
        if (attempted < 0 || sent < 0 || failed < 0 || remaining < 0) {
            throw new IllegalArgumentException("Broadcast counts must not be negative");
        }
        if (sent + failed != attempted) {
            throw new IllegalArgumentException("Broadcast sent and failed must add up to attempted");
        }
    }

    /** A broadcast that ran through every user, so nothing remains. */
    public BroadcastResult(int attempted, int sent, int failed, boolean testMode) {
        this(attempted, sent, failed, testMode, 0);
    }

    /** True when the loop stopped early because its thread was interrupted. */
    public boolean interrupted() {
        return remaining > 0;
    }

    /** Every user the broadcast was addressed to. */
    public int total() {
        return attempted + remaining;
    }
}
