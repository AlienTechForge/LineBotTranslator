package com.linetranslate.bot.service.ocr;

/**
 * A bug inside an OCR adapter, such as a null or malformed response that its own parsing could
 * not handle, as opposed to the provider or transport failing. It is still an
 * {@link OcrProcessingException}, so the pipeline falls back to AI recognition as before, but the
 * pipeline reports it as a defect instead of an unavailable provider.
 */
final class OcrDefectException extends OcrProcessingException {

    OcrDefectException(String message, Throwable cause) {
        super(message, cause);
    }
}
