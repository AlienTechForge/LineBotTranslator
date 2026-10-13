package com.linetranslate.bot.service.ocr;

/**
 * The OCR provider did not answer within the client deadline. It is an
 * {@link OcrProcessingException} so every existing caller still treats it as a recognition
 * failure, but a timeout is a different operational signal from a rejected or unreadable image:
 * it points at provider latency or the configured bound rather than at the request itself.
 */
public class OcrTimeoutException extends OcrProcessingException {

    public OcrTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
