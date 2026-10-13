package com.linetranslate.bot.service.ocr;

import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import com.linetranslate.bot.logging.SafeLog;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
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
    private final OcrRegionSegmenter regionSegmenter = new OcrRegionSegmenter();

    public GoogleVisionOcrService(ObjectProvider<ImageAnnotatorClient> visionClientProvider) {
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
            StringBuilder textBuilder = new StringBuilder();
            if (response.getResponsesCount() > 0) {
                TextAnnotation textAnnotation = response.getResponses(0).getFullTextAnnotation();
                if (textAnnotation != null) {
                    return textAnnotation.getText();
                }

                // 如果沒有完整文本註釋，則嘗試獲取單獨的文本註釋
                for (EntityAnnotation annotation : response.getResponses(0).getTextAnnotationsList()) {
                    if (textBuilder.length() > 0) {
                        textBuilder.append("\n");
                    }
                    textBuilder.append(annotation.getDescription());
                }
            }

            String result = textBuilder.toString();
            log.info("OCR 識別完成: content={}", SafeLog.content(result));
            return result;

        } catch (IOException e) {
            log.error("OCR 識別失敗: failure={}", SafeLog.failure(e));
            throw new OcrProcessingException("Google Vision could not read image bytes", e);
        } catch (RuntimeException failure) {
            if (failure instanceof OcrProcessingException ocrFailure) {
                throw ocrFailure;
            }
            throw new OcrProcessingException("Google Vision OCR failed", failure);
        }
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

    @Override
    public List<OcrRegion> recognizeRegions(InputStream imageStream) {
        return annotateDocument(imageStream).regions();
    }

    /** The document response already carries the page text, so no second request is needed. */
    @Override
    public OcrRecognition recognize(InputStream imageStream) {
        DocumentAnnotation document = annotateDocument(imageStream);
        return document.regions().isEmpty()
                ? OcrRecognition.plain(document.fullText())
                : OcrRecognition.structured(document.regions());
    }

    private DocumentAnnotation annotateDocument(InputStream imageStream) {
        if (visionClient == null) {
            throw new OcrProcessingException("Google Vision client is unavailable");
        }

        try {
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
                return new DocumentAnnotation(List.of(), "");
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
                            List<OcrWord> words = paragraph.getWordsList().stream().map(word -> {
                                String wordText = wordText(word);
                                List<OcrSymbol> symbols = word.getSymbolsList().stream()
                                        .map(symbol -> new OcrSymbol(symbol.getText(), points(symbol.getBoundingBox()),
                                                symbol.getConfidence(), symbol.getConfidence() > 0))
                                        .toList();
                                return new OcrWord(wordText, points(word.getBoundingBox()), word.getConfidence(),
                                        word.getConfidence() > 0, symbols);
                            }).toList();
                            List<OcrDetectedLanguage> languages = detectedLanguages(paragraph);
                            regions.add(new OcrRegion(
                                    stableId(readingOrder, text, polygon), text, polygon, words,
                                    paragraph.getConfidence(), paragraph.getConfidence() > 0,
                                    OcrBlockType.TEXT, languages, readingOrder++));
                        }
                    }
                }
            }
            List<OcrRegion> segmented = regionSegmenter.segment(regions);
            log.info("結構化 OCR 完成: providerRegions={}, layoutRegions={}",
                    regions.size(), segmented.size());
            return new DocumentAnnotation(segmented, fullText.getText());
        } catch (IOException e) {
            log.error("OCR 識別失敗: failure={}", SafeLog.failure(e));
            throw new OcrProcessingException("Google Vision could not read image bytes", e);
        } catch (RuntimeException failure) {
            if (failure instanceof OcrProcessingException ocrFailure) {
                throw ocrFailure;
            }
            throw new OcrProcessingException("Google Vision located OCR failed", failure);
        }
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

    private record DocumentAnnotation(List<OcrRegion> regions, String fullText) {
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
