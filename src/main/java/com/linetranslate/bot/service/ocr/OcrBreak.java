package com.linetranslate.bot.service.ocr;

/**
 * What the provider recorded after a word. A wrapped column of vertical writing ends in
 * {@link #WRAP} and the last column of a paragraph in {@link #PARAGRAPH_END}, which is how a flow
 * of columns is told from a list of items that merely stand side by side.
 */
public enum OcrBreak {
    /** No break was recorded, or the provider does not say. */
    NONE,
    /** An ordinary space. */
    SPACE,
    /** The line (or, in vertical writing, the column) wrapped and the paragraph goes on. */
    WRAP,
    /** The paragraph ends here. */
    PARAGRAPH_END,
    /** The line ends in a hyphen the provider left out of the symbols. */
    HYPHEN
}
