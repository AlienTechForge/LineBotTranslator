package com.linetranslate.bot.service.imagetranslator;

import java.util.Locale;

/**
 * The image-translator service could not be used: it did not answer, answered with an HTTP error,
 * or answered with something that is not its documented JSON. The message carries the HTTP status
 * and the service's {@code reason} code only; the service's {@code detail} sentence is available
 * from {@link #detail()}. Neither the token nor a URL is ever put in here.
 */
public class ImageTranslatorException extends RuntimeException {

    public enum Kind {
        /** No answer: the connection failed. */
        UNREACHABLE,
        /** No answer in time. */
        TIMEOUT,
        /** The service answered with a status other than 200. */
        HTTP,
        /** The service answered 200 with a body the client cannot use. */
        MALFORMED
    }

    private final Kind kind;
    private final int httpStatus;
    private final String reason;
    private final String detail;

    public ImageTranslatorException(Kind kind, int httpStatus, String reason, String detail, Throwable cause) {
        super(message(kind, httpStatus, reason), cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.reason = reason;
        this.detail = detail;
    }

    static ImageTranslatorException http(int status, String reason, String detail) {
        return new ImageTranslatorException(Kind.HTTP, status, reason, detail, null);
    }

    static ImageTranslatorException of(Kind kind, Throwable cause) {
        return new ImageTranslatorException(kind, 0, null, null, cause);
    }

    private static String message(Kind kind, int httpStatus, String reason) {
        StringBuilder text = new StringBuilder("image-translator ").append(kind.name().toLowerCase(Locale.ROOT));
        if (httpStatus > 0) {
            text.append(": HTTP ").append(httpStatus);
        }
        if (reason != null && !reason.isBlank()) {
            text.append(" (").append(reason.replaceAll("[^a-zA-Z0-9._:-]", "_")).append(')');
        }
        return text.toString();
    }

    public Kind kind() {
        return kind;
    }

    /** The HTTP status, or 0 when there was no HTTP answer. */
    public int httpStatus() {
        return httpStatus;
    }

    /** The service's machine readable reason of an error answer; null when the body was not its error JSON. */
    public String reason() {
        return reason;
    }

    /** The service's one-sentence explanation of an error answer; null when there was none. */
    public String detail() {
        return detail;
    }

    /** True for 409 {@code request_id_in_use}: the service already has this request id. */
    public boolean isDuplicateRequest() {
        return kind == Kind.HTTP && httpStatus == 409;
    }
}
