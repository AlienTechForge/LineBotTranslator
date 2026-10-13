package com.linetranslate.bot.service.ocr;

/**
 * The translated image could not be encoded within the output cap. A renderer that fails this way
 * is behaving as designed: the pipeline degrades to a text translation. Any other exception from
 * rendering is a defect.
 */
final class RenderedImageEncodingException extends IllegalStateException {

    RenderedImageEncodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
