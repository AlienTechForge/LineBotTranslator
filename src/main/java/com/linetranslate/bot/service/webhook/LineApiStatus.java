package com.linetranslate.bot.service.webhook;

import com.linecorp.bot.client.base.exception.AbstractLineClientException;
import com.linetranslate.bot.logging.SafeLog;

/** Reads the HTTP status LINE answered a failed API call with, for logs. */
final class LineApiStatus {

    private static final int MAX_CAUSE_DEPTH = 4;

    private LineApiStatus() {
    }

    /**
     * The HTTP status of the LINE client failure in {@code failure}'s cause chain, or {@code none}
     * when the call failed before LINE answered (timeout, connection reset) or for another reason.
     */
    static String of(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && current != null; depth++) {
            if (current instanceof AbstractLineClientException clientFailure) {
                return String.valueOf(SafeLog.httpStatus(clientFailure.getCode()));
            }
            current = current.getCause();
        }
        return "none";
    }
}
