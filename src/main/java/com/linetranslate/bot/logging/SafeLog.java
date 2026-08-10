package com.linetranslate.bot.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Produces production-safe metadata for values that must never be written to logs verbatim.
 */
public final class SafeLog {

    private static final int USER_FINGERPRINT_LENGTH = 12;
    private static final int MAX_CAUSE_DEPTH = 4;

    private SafeLog() {
    }

    public static String user(String userId) {
        if (userId == null || userId.isBlank()) {
            return "usr_anonymous";
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(userId.getBytes(StandardCharsets.UTF_8));
            return "usr_" + HexFormat.of().formatHex(digest).substring(0, USER_FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String content(String value) {
        return "chars=" + (value == null ? 0 : value.length());
    }

    public static String endpoint(String value) {
        if (value == null || value.isBlank()) {
            return "unconfigured";
        }

        int schemeSeparator = value.indexOf("://");
        if (schemeSeparator < 0) {
            return "configured";
        }

        String scheme = value.substring(0, schemeSeparator + 3);
        String address = value.substring(schemeSeparator + 3);
        int userInfoEnd = address.lastIndexOf('@');
        if (userInfoEnd >= 0) {
            address = address.substring(userInfoEnd + 1);
        }

        int queryStart = address.indexOf('?');
        if (queryStart >= 0) {
            address = address.substring(0, queryStart);
        }
        int fragmentStart = address.indexOf('#');
        if (fragmentStart >= 0) {
            address = address.substring(0, fragmentStart);
        }
        return scheme + address;
    }

    public static String failure(Throwable failure) {
        if (failure == null) {
            return "UnknownFailure";
        }

        StringBuilder types = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (depth > 0) {
                types.append("<-");
            }
            types.append(current.getClass().getSimpleName());
            current = current.getCause();
            depth++;
        }
        return types.toString();
    }

    public static int httpStatus(int status) {
        return status;
    }

    public static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
