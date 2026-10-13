package com.linetranslate.bot.testing;

import java.lang.ref.WeakReference;

/**
 * Asks the collector whether an object is still reachable, for tests of how long a request keeps a
 * large buffer alive. The test must hold only the {@link WeakReference}: build the object in a
 * helper that returns, so no local of the test method keeps it.
 */
public final class Reachability {

    private static final int ATTEMPTS = 25;
    private static final long PAUSE_MILLIS = 20;

    private Reachability() {
    }

    /** True once the referent has been collected; asks for a collection a bounded number of times. */
    public static boolean collected(WeakReference<?> reference) {
        for (int attempt = 0; attempt < ATTEMPTS && reference.get() != null; attempt++) {
            System.gc();
            try {
                Thread.sleep(PAUSE_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return reference.get() == null;
    }
}
