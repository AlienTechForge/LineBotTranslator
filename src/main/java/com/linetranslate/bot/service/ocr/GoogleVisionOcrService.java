package com.linetranslate.bot.service.ocr;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import com.linetranslate.bot.logging.SafeLog;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
@Slf4j
public class GoogleVisionOcrService implements OcrService {

    private final ImageAnnotatorClient visionClient;
    private final OcrRegionSegmenter regionSegmenter;

    public GoogleVisionOcrService(ObjectProvider<ImageAnnotatorClient> visionClientProvider) {
        this(visionClientProvider, new OcrRegionSegmenter());
    }

    @Autowired
    public GoogleVisionOcrService(
            ObjectProvider<ImageAnnotatorClient> visionClientProvider, OcrRegionSegmenter regionSegmenter) {
        this.regionSegmenter = regionSegmenter;
        this.visionClient = visionClientProvider.getIfAvailable();
        if (visionClient != null) {
            log.info("Google Vision OCR 服務初始化成功");
        } else {
            log.warn("Google Vision OCR 服務未能正確初始化，OCR 功能將不可用");
        }
    }

    @Override
    public String recognizeText(InputStream imageStream) {
        if (visionClient == null) {
            throw new OcrProcessingException("Google Vision client is unavailable");
        }

        try {
            // 讀取圖片數據
            byte[] imageData = imageStream.readAllBytes();
            ByteString imgBytes = ByteString.copyFrom(imageData);

            // 創建圖片
            Image image = Image.newBuilder().setContent(imgBytes).build();

            // 設置特徵類型為文本檢測
            Feature feature = Feature.newBuilder().setType(Feature.Type.TEXT_DETECTION).build();

            // 創建請求
            AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                    .addFeatures(feature)
                    .setImage(image)
                    .build();

            // 執行 OCR 請求
            BatchAnnotateImagesResponse response = visionClient.batchAnnotateImages(List.of(request));

            if (response.getResponsesCount() > 0 && response.getResponses(0).hasError()) {
                throw new OcrProcessingException("Google Vision returned an OCR error");
            }

            // 提取文本
            String result = "";
            if (response.getResponsesCount() > 0) {
                AnnotateImageResponse annotationResponse = response.getResponses(0);
                // Protobuf getters never return null; an absent annotation is the default
                // instance, whose text is empty.
                String fullText = annotationResponse.getFullTextAnnotation().getText();
                if (!fullText.isEmpty()) {
                    return fullText;
                }

                // 如果沒有完整文本註釋，則改用文本註釋；第一筆已涵蓋整段文字，
                // 其餘為逐字詞項目，全部串接會讓文字重複
                if (annotationResponse.getTextAnnotationsCount() > 0) {
                    result = annotationResponse.getTextAnnotations(0).getDescription();
                }
            }

            log.info("OCR 識別完成: content={}", SafeLog.content(result));
            return result;

        } catch (IOException e) {
            log.error("OCR 識別失敗: failure={}", SafeLog.failure(e));
            throw new OcrProcessingException("Google Vision could not read image bytes", e);
        } catch (RuntimeException failure) {
            throw classify(failure, "Google Vision OCR failed");
        }
    }

    /**
     * Keeps an {@link OcrProcessingException} as it is and turns a client deadline into an
     * {@link OcrTimeoutException}, so a slow provider is told apart from a rejected image by
     * type rather than by parsing a message. The gax client raises the deadline as an
     * {@link ApiException} with status {@code DEADLINE_EXCEEDED}. Only an {@link ApiException}
     * and a transport {@link UncheckedIOException} are provider failures; any other
     * {@code RuntimeException} came from this adapter's own code and is wrapped in an
     * {@link OcrDefectException} so it is not mistaken for an outage.
     */
    private OcrProcessingException classify(RuntimeException failure, String genericMessage) {
        if (failure instanceof OcrProcessingException ocrFailure) {
            return ocrFailure;
        }
        if (failure instanceof ApiException apiFailure) {
            if (apiFailure.getStatusCode().getCode() == StatusCode.Code.DEADLINE_EXCEEDED) {
                log.warn("Google Vision OCR 逾時: timeoutMs={}, failure={}",
                        configuredTimeoutMs(), SafeLog.failure(failure));
                return new OcrTimeoutException("Google Vision OCR timed out", failure);
            }
            return new OcrProcessingException(genericMessage, failure);
        }
        if (failure instanceof UncheckedIOException) {
            return new OcrProcessingException(genericMessage, failure);
        }
        return new OcrDefectException(genericMessage, failure);
    }

    /** The bound the client was built with, or -1 when its settings are not available. */
    private long configuredTimeoutMs() {
        ImageAnnotatorSettings settings = visionClient.getSettings();
        return settings == null
                ? -1
                : settings.batchAnnotateImagesSettings().getRetrySettings()
                        .getTotalTimeoutDuration().toMillis();
    }

    @Override
    public List<TextBlock> recognizeTextWithLocations(InputStream imageStream) {
        return recognizeRegions(imageStream).stream().map(region -> {
            int minX = region.polygon().stream().mapToInt(OcrPoint::x).min().orElse(0);
            int minY = region.polygon().stream().mapToInt(OcrPoint::y).min().orElse(0);
            int maxX = region.polygon().stream().mapToInt(OcrPoint::x).max().orElse(minX);
            int maxY = region.polygon().stream().mapToInt(OcrPoint::y).max().orElse(minY);
            return new TextBlock(region.text(), minX, minY, maxX - minX, maxY - minY, region.confidence());
        }).toList();
    }

    /**
     * The paragraphs as Vision reported them, segmented in the frame of the submitted image. The
     * page is never turned upright here: a caller of this method has no frame to learn that from,
     * and draws on the image it sent. {@link #recognize} is the path that brings a rotated page
     * upright and says so.
     */
    @Override
    public List<OcrRegion> recognizeRegions(InputStream imageStream) {
        return annotateDocument(imageStream, false).regions();
    }

    /**
     * The document response already carries the page text, so no second request is needed. A page
     * found to be turned a quarter comes back upright, with the turn in {@link OcrRecognition#frame()}.
     */
    @Override
    public OcrRecognition recognize(InputStream imageStream) {
        DocumentAnnotation document = annotateDocument(imageStream, true);
        return document.regions().isEmpty()
                ? OcrRecognition.plain(document.fullText())
                : OcrRecognition.structured(document.regions(), document.frame());
    }

    /**
     * One request, read but not acted on: the paragraphs in the provider's frame, the size of the
     * page, and what the detector makes of them. For the opt-in geometry dump and for tests.
     */
    ProviderInspection inspect(InputStream imageStream) {
        try {
            ProviderPage page = readPage(imageStream);
            return new ProviderInspection(page, PageOrientationDetector.detect(page.regions()));
        } catch (IOException e) {
            log.error("OCR 識別失敗: failure={}", SafeLog.failure(e));
            throw new OcrProcessingException("Google Vision could not read image bytes", e);
        } catch (RuntimeException failure) {
            throw classify(failure, "Google Vision located OCR failed");
        }
    }

    record ProviderInspection(ProviderPage page, PageOrientation orientation) {
    }

    /** What one response says before any decision: the paragraphs, the text and the page size. */
    record ProviderPage(List<OcrRegion> regions, String fullText, int pageCount, int width, int height) {
    }

    private DocumentAnnotation annotateDocument(InputStream imageStream, boolean normalizePage) {
        try {
            ProviderPage page = readPage(imageStream);
            List<OcrRegion> regions = page.regions();
            PageFrame frame = PageFrame.NONE;
            if (normalizePage && !regions.isEmpty()) {
                PageOrientation orientation = PageOrientationDetector.detect(regions);
                int quarter = turnToApply(orientation, page);
                int vertical = 0;
                if (quarter != 0) {
                    regions = QuarterTurns.toUpright(regions, quarter, page.width(), page.height());
                } else {
                    // Upright vertical writing has the frame of a turned page. It is not turned, only
                    // named, so that it is split and laid out as columns.
                    regions = PageOrientationDetector.markVerticalWriting(regions, orientation,
                            PageOrientationDetector.SYMBOL_FRAME_FOLLOWS_GLYPH);
                    vertical = (int) regions.stream().filter(region -> region.writingMode().vertical()).count();
                }
                frame = new PageFrame(quarter, page.width(), page.height(), orientation.verdict(),
                        quarter != 0 && orientation.retryAdvised(), orientation.reliableShare());
                logDecision(orientation, page, quarter, vertical);
            }
            List<OcrRegion> segmented = regionSegmenter.segment(regions);
            log.info("結構化 OCR 完成: providerRegions={}, layoutRegions={}",
                    page.regions().size(), segmented.size());
            return new DocumentAnnotation(segmented, page.fullText(), frame);
        } catch (IOException e) {
            log.error("OCR 識別失敗: failure={}", SafeLog.failure(e));
            throw new OcrProcessingException("Google Vision could not read image bytes", e);
        } catch (RuntimeException failure) {
            throw classify(failure, "Google Vision located OCR failed");
        }
    }

    /**
     * The quarters to turn the page by to make it upright: those of a rotated page, when the
     * response describes exactly one page of a known size to map by, and 0 otherwise.
     */
    private static int turnToApply(PageOrientation orientation, ProviderPage page) {
        if (orientation.verdict() != PageOrientation.Verdict.ROTATED
                || !QuarterTurns.isTurn(orientation.quarterTurns())
                || page.pageCount() != 1 || page.width() <= 0 || page.height() <= 0) {
            return 0;
        }
        return orientation.quarterTurns();
    }

    /**
     * One line per image. Counts, ratios, angles and sizes only: no source content, no symbol and
     * no region id reaches it, because those are short hashes of the source and add nothing to an
     * aggregate decision.
     */
    private static void logDecision(
            PageOrientation orientation, ProviderPage page, int appliedQuarter, int verticalRegions) {
        String action = appliedQuarter != 0 ? "normalize"
                : verticalRegions > 0 ? "mark-vertical"
                : orientation.verdict() != PageOrientation.Verdict.ROTATED ? "none"
                : page.pageCount() != 1 ? "skip-pages" : "skip-page-size";
        log.info("Page orientation decision: verdict={}, quarterTurns={}, regions={}, reliableRegions={}, "
                        + "dominantShare={}, skewedShare={}, reliableShare={}, cjkShare={}, symbolSamples={}, "
                        + "uprightGlyphShare={}, rotatedGlyphShare={}, punctuationSamples={}, "
                        + "punctuationOffset={}, action={}, verticalRegions={}, retryAdvised={}, "
                        + "pageWidth={}, pageHeight={}",
                orientation.verdict(), orientation.quarterTurns() < 0 ? "n/a" : orientation.quarterTurns(),
                orientation.regions(), orientation.reliableRegions(),
                format(orientation.dominantShare()), format(orientation.skewedShare()),
                format(orientation.reliableShare()), format(orientation.cjkShare()),
                orientation.symbolSamples(), format(orientation.uprightGlyphShare()),
                format(orientation.rotatedGlyphShare()), orientation.punctuationSamples(),
                format(orientation.punctuationOffset()), action, verticalRegions,
                appliedQuarter != 0 && orientation.retryAdvised(), page.width(), page.height());
    }

    private static String format(double value) {
        return value < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** Sends the one request and turns the response into provider paragraphs. */
    private ProviderPage readPage(InputStream imageStream) throws IOException {
        if (visionClient == null) {
            throw new OcrProcessingException("Google Vision client is unavailable");
        }
        byte[] imageData = imageStream.readAllBytes();
        ByteString imgBytes = ByteString.copyFrom(imageData);
        Image image = Image.newBuilder().setContent(imgBytes).build();
        Feature feature = Feature.newBuilder()
                .setType(Feature.Type.DOCUMENT_TEXT_DETECTION)
                .build();
        AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                .addFeatures(feature)
                .setImage(image)
                .build();
        BatchAnnotateImagesResponse response = visionClient.batchAnnotateImages(List.of(request));
        if (response.getResponsesCount() == 0) {
            return new ProviderPage(List.of(), "", 0, 0, 0);
        }
        var annotationResponse = response.getResponses(0);
        if (annotationResponse.hasError()) {
            throw new OcrProcessingException("Google Vision returned an OCR error");
        }

        List<OcrRegion> regions = new ArrayList<>();
        TextAnnotation fullText = annotationResponse.getFullTextAnnotation();
        int readingOrder = 0;
        for (var page : fullText.getPagesList()) {
            for (var block : page.getBlocksList()) {
                if (block.getBlockType() != Block.BlockType.TEXT) {
                    continue;
                }
                for (var paragraph : block.getParagraphsList()) {
                    String text = paragraphText(paragraph);
                    List<OcrPoint> polygon = points(paragraph.getBoundingBox());
                    if (!text.isBlank() && polygon.size() >= 4) {
                        List<Word> sourceWords = paragraph.getWordsList();
                        List<OcrWord> words = new ArrayList<>(sourceWords.size());
                        for (int index = 0; index < sourceWords.size(); index++) {
                            Word word = sourceWords.get(index);
                            List<OcrSymbol> symbols = word.getSymbolsList().stream()
                                    .map(symbol -> new OcrSymbol(symbol.getText(), points(symbol.getBoundingBox()),
                                            symbol.getConfidence(), symbol.getConfidence() > 0))
                                    .toList();
                            Word following = index + 1 < sourceWords.size() ? sourceWords.get(index + 1) : null;
                            words.add(new OcrWord(wordText(word), points(word.getBoundingBox()),
                                    word.getConfidence(), word.getConfidence() > 0, symbols,
                                    breakAfter(word, following)));
                        }
                        List<OcrDetectedLanguage> languages = detectedLanguages(paragraph);
                        regions.add(new OcrRegion(
                                stableId(readingOrder, text, polygon), text, polygon, words,
                                paragraph.getConfidence(), paragraph.getConfidence() > 0,
                                OcrBlockType.TEXT, languages, readingOrder++));
                    }
                }
            }
        }
        // Vision answers an image with one page. Its size is the size of the image that was sent.
        int pages = fullText.getPagesCount();
        return new ProviderPage(List.copyOf(regions), fullText.getText(), pages,
                pages == 1 ? fullText.getPages(0).getWidth() : 0,
                pages == 1 ? fullText.getPages(0).getHeight() : 0);
    }

    /**
     * A paragraph's text, rebuilt with the separators Vision detected between its words. Vision
     * returns CJK text one glyph or short run per word, so a space between every pair of words
     * would split every character apart. A line end collapses to the script rule because a region
     * is a single block that the renderer wraps again, and newlines already separate regions.
     * Paragraphs that carry no break at all, such as hand-built fixtures, use the script rule
     * throughout.
     */
    private static String paragraphText(Paragraph paragraph) {
        List<Word> words = paragraph.getWordsList().stream()
                .filter(word -> !wordText(word).isBlank())
                .toList();
        boolean breaksDetected = words.stream()
                .flatMap(word -> word.getSymbolsList().stream())
                .anyMatch(symbol -> symbol.getProperty().hasDetectedBreak());
        StringBuilder text = new StringBuilder();
        Word previous = null;
        for (Word word : words) {
            String value = wordText(word);
            if (previous != null) {
                text.append(breaksDetected
                        ? separator(breakBetween(previous, word), text, value)
                        : OcrWordJoiner.scriptSeparator(text, value));
            }
            text.append(value);
            previous = word;
        }
        return text.toString();
    }

    private static String wordText(Word word) {
        return word.getSymbolsList().stream().map(Symbol::getText).reduce("", String::concat);
    }

    /** The break Vision recorded after {@code previous}, or before {@code next} when prefixed. */
    private static TextAnnotation.DetectedBreak.BreakType breakBetween(Word previous, Word next) {
        if (next.getSymbolsCount() > 0) {
            TextAnnotation.TextProperty property = next.getSymbols(0).getProperty();
            if (property.hasDetectedBreak() && property.getDetectedBreak().getIsPrefix()) {
                return property.getDetectedBreak().getType();
            }
        }
        if (previous.getSymbolsCount() > 0) {
            TextAnnotation.TextProperty property =
                    previous.getSymbols(previous.getSymbolsCount() - 1).getProperty();
            if (property.hasDetectedBreak() && !property.getDetectedBreak().getIsPrefix()) {
                return property.getDetectedBreak().getType();
            }
        }
        return TextAnnotation.DetectedBreak.BreakType.UNKNOWN;
    }

    /**
     * The break the provider recorded after {@code word}: the one that precedes or follows it at the
     * boundary with {@code following}, or on its own last symbol when it ends the paragraph.
     */
    private static OcrBreak breakAfter(Word word, Word following) {
        TextAnnotation.DetectedBreak.BreakType type;
        if (following != null) {
            type = breakBetween(word, following);
        } else if (word.getSymbolsCount() > 0
                && word.getSymbols(word.getSymbolsCount() - 1).getProperty().hasDetectedBreak()) {
            type = word.getSymbols(word.getSymbolsCount() - 1).getProperty().getDetectedBreak().getType();
        } else {
            type = TextAnnotation.DetectedBreak.BreakType.UNKNOWN;
        }
        return switch (type) {
            case SPACE, SURE_SPACE -> OcrBreak.SPACE;
            case EOL_SURE_SPACE -> OcrBreak.WRAP;
            case LINE_BREAK -> OcrBreak.PARAGRAPH_END;
            case HYPHEN -> OcrBreak.HYPHEN;
            default -> OcrBreak.NONE;
        };
    }

    private static String separator(
            TextAnnotation.DetectedBreak.BreakType type, CharSequence left, String right) {
        return switch (type) {
            case SPACE, SURE_SPACE -> " ";
            case EOL_SURE_SPACE, LINE_BREAK -> OcrWordJoiner.scriptSeparator(left, right);
            // Vision leaves an end-of-line hyphen out of the symbols and reports it as a break.
            case HYPHEN -> "-";
            default -> "";
        };
    }

    private record DocumentAnnotation(List<OcrRegion> regions, String fullText, PageFrame frame) {
    }

    private static List<OcrPoint> points(BoundingPoly polygon) {
        if (polygon == null) return List.of();
        return polygon.getVerticesList().stream().map(v -> new OcrPoint(v.getX(), v.getY())).toList();
    }

    private static List<OcrDetectedLanguage> detectedLanguages(Paragraph paragraph) {
        java.util.Map<String, Float> confidenceByCode = new java.util.LinkedHashMap<>();
        paragraph.getProperty().getDetectedLanguagesList().forEach(language -> {
            if (!language.getLanguageCode().isBlank()) {
                confidenceByCode.merge(language.getLanguageCode(), language.getConfidence(), Math::max);
            }
        });
        paragraph.getWordsList().forEach(word -> word.getProperty().getDetectedLanguagesList().forEach(language -> {
            if (!language.getLanguageCode().isBlank()) {
                confidenceByCode.merge(language.getLanguageCode(), language.getConfidence(), Math::max);
            }
        }));
        return confidenceByCode.entrySet().stream()
                .map(entry -> new OcrDetectedLanguage(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparing(OcrDetectedLanguage::confidence).reversed())
                .toList();
    }

    private static String stableId(int order, String text, List<OcrPoint> polygon) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = order + "|" + text.strip() + "|" + polygon;
            String hash = HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return "r-%04d-%s".formatted(order + 1, hash.substring(0, 12));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
