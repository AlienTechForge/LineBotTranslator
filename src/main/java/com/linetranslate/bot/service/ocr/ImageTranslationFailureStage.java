package com.linetranslate.bot.service.ocr;

/** Where an image translation ended without anything to send; each stage has its own reply. */
public enum ImageTranslationFailureStage {
    /** The picture could not be downloaded from LINE or is not an acceptable image. */
    DOWNLOAD,
    /** The service found no text in the picture. */
    NO_TEXT,
    /** The service answered, but could not translate (any reason but a budget), or already had this request. */
    TRANSLATION,
    /** The service did not answer, answered with an HTTP error, or answered with something unusable. */
    SERVICE_UNAVAILABLE,
    /** The service's monthly budget is used up. */
    BUDGET,
    /** The thread was interrupted. */
    CANCELLED
}
