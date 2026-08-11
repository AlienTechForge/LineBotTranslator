package com.linetranslate.bot.service.ocr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Single fail-closed qualification and protected-token policy. */
@Component
public class OcrRegionQualificationPolicy {

    private static final int MAX_TEXT_LENGTH = 4_000;
    private static final Pattern NATURAL_LETTER = Pattern.compile("\\p{L}");
    private static final Pattern DECORATION = Pattern.compile("^[\\p{S}\\p{P}_~^`|\\\\/]+$");
    private static final Pattern PRESERVE_ONLY = Pattern.compile(
            "(?iu)^(?:https?://\\S+|www\\.\\S+|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|[+()\\d .-]{7,}|(?:[$€£¥₩]\\s*)?\\d[\\d.,:/-]*(?:\\s?(?:%|°[cf]|kg|g|mg|km|m|cm|mm|ml|l|gb|mb|v|w))?|[a-z]{1,6}[-_]?\\d[\\w.-]*)$");
    private static final Pattern PROTECTED = Pattern.compile(
            "(?iu)(?:\\d+(?:[.,]\\d+)?\\s?(?:%|°[cf]|kg|mg|km|cm|mm|ml|gb|mb|g|m|l|v|w)|\\d{1,4}(?:[./:-]\\d{1,4})+|[$€£¥₩]\\s?\\d+(?:[.,]\\d+)*)");

    public OcrRegionDecision decide(OcrRegion region, float confidenceThreshold) {
        if (region == null || region.text().isBlank() || region.text().length() > MAX_TEXT_LENGTH) {
            return reject(region, "invalid-text");
        }
        if (region.blockType() != OcrBlockType.TEXT) return reject(region, "non-text-block");
        if (!region.validGeometry()) return reject(region, "invalid-geometry");
        if (!region.confidenceKnown() || region.confidence() < confidenceThreshold) {
            return reject(region, "untrusted-confidence");
        }
        String text = region.text().strip();
        long letterCount = text.codePoints().filter(Character::isLetter).count();
        if (letterCount == 1 && region.languages().isEmpty()) return reject(region, "untrusted-isolated-glyph");
        if (!wordConfidenceIsSafe(region, confidenceThreshold)) return reject(region, "untrusted-word-confidence");
        if (!NATURAL_LETTER.matcher(text).find()) {
            return PRESERVE_ONLY.matcher(text).matches()
                    ? preserve(region, "non-translatable-token")
                    : reject(region, "low-information");
        }
        if (DECORATION.matcher(text).matches()) return reject(region, "decoration");
        if (scriptConflicts(region)) return reject(region, "language-script-conflict");
        if (PRESERVE_ONLY.matcher(text).matches()) return preserve(region, "protected-region");
        return new OcrRegionDecision(region, OcrQualification.TRANSLATE, "eligible", protectedTokens(text));
    }

    public boolean preservesTokens(OcrRegionDecision decision, String translatedText) {
        if (decision == null || translatedText == null) return false;
        int cursor = 0;
        for (String token : decision.protectedTokens()) {
            int found = translatedText.indexOf(token, cursor);
            if (found < 0) return false;
            cursor = found + token.length();
        }
        return true;
    }

    private static List<String> protectedTokens(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = PROTECTED.matcher(text);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static boolean scriptConflicts(OcrRegion region) {
        if (region.languages().isEmpty()) return false;
        String language = region.languages().get(0).code();
        if (region.languages().get(0).confidence() < .7f) return false;
        long letters = region.text().codePoints().filter(Character::isLetter).count();
        if (letters == 0) return false;
        return switch (language) {
            case "ko" -> region.text().codePoints().noneMatch(cp -> Character.UnicodeScript.of(cp)
                    == Character.UnicodeScript.HANGUL);
            case "zh" -> region.text().codePoints().noneMatch(cp -> Character.UnicodeScript.of(cp)
                    == Character.UnicodeScript.HAN);
            case "ja" -> region.text().codePoints().noneMatch(cp -> {
                Character.UnicodeScript script = Character.UnicodeScript.of(cp);
                return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA;
            });
            case "vi", "en", "fr", "de", "es", "it", "pt" -> region.text().codePoints()
                    .filter(Character::isLetter)
                    .noneMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN);
            default -> false;
        };
    }

    private static boolean wordConfidenceIsSafe(OcrRegion region, float threshold) {
        if (region.words().isEmpty()) return true;
        if (region.words().stream().anyMatch(word -> !word.confidenceKnown())) return false;
        double wordAverage = region.words().stream().mapToDouble(OcrWord::confidence).average().orElse(0);
        if (wordAverage < threshold * .8) return false;
        List<OcrSymbol> symbols = region.words().stream().flatMap(word -> word.symbols().stream()).toList();
        if (symbols.isEmpty()) return true;
        if (symbols.stream().anyMatch(symbol -> !symbol.confidenceKnown())) return false;
        return symbols.stream().mapToDouble(OcrSymbol::confidence).average().orElse(0) >= threshold * .75;
    }

    private static OcrRegionDecision reject(OcrRegion region, String reason) {
        return new OcrRegionDecision(region, OcrQualification.REJECT, reason, List.of());
    }

    private static OcrRegionDecision preserve(OcrRegion region, String reason) {
        return new OcrRegionDecision(region, OcrQualification.PRESERVE, reason, List.of());
    }
}
