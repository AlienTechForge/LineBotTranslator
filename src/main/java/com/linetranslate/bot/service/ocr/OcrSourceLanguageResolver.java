package com.linetranslate.bot.service.ocr;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Weighted dominant-language resolver; returns null when evidence is unsafe/mixed. */
@Component
public class OcrSourceLanguageResolver {
    public String resolve(List<OcrRegion> regions) {
        Map<String, Double> scores = new HashMap<>();
        for (OcrRegion region : regions == null ? List.<OcrRegion>of() : regions) {
            if (region.blockType() != OcrBlockType.TEXT || region.text().isBlank()) continue;
            double weight = Math.max(1, region.text().codePoints().filter(Character::isLetter).count())
                    * (region.confidenceKnown() ? region.confidence() : 0.25);
            for (OcrDetectedLanguage language : region.languages()) {
                scores.merge(normalize(language.code()), weight * Math.max(.1, language.confidence()), Double::sum);
            }
            if (region.languages().isEmpty()) {
                String script = scriptLanguage(region.text());
                if (script != null) scores.merge(script, weight * .5, Double::sum);
            }
        }
        if (scores.isEmpty()) return null;
        List<Map.Entry<String, Double>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).toList();
        double total = ranked.stream().mapToDouble(Map.Entry::getValue).sum();
        return ranked.get(0).getValue() / total >= .55 ? ranked.get(0).getKey() : null;
    }

    private static String normalize(String value) {
        String code = value.toLowerCase(Locale.ROOT);
        int separator = code.indexOf('-');
        return separator < 0 ? code : code.substring(0, separator);
    }

    private static String scriptLanguage(String text) {
        long hangul = text.codePoints().filter(cp -> cp >= 0xAC00 && cp <= 0xD7AF).count();
        long vietnamese = text.codePoints().filter(cp -> "ăâđêôơưĂÂĐÊÔƠƯ".indexOf(cp) >= 0
                || cp >= 0x1EA0 && cp <= 0x1EF9).count();
        long cjk = text.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN).count();
        if (hangul > 0) return "ko";
        if (vietnamese > 0) return "vi";
        if (cjk > 0) return "zh";
        return null;
    }
}
