package com.linetranslate.bot.service.translation;

import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Canonical target-locale metadata and conservative output-script validation. */
@Component
public class TargetLocalePolicy {
    private static final String SIMPLIFIED_ONLY =
            "这为个们来时说对会还国发后里经从过样应间开关无实进学长现点动产种把当与义气几电车书见观东门风叶体台万网历广术号龙马鸟鱼爱听话语头面边达办厂医药优严伤热护续绩觉让带给该将只并区块显线简译误识";
    private static final String TRADITIONAL_ONLY =
            "這為個們來時說對會還國發後裡經從過樣應間開關無實進學長現點動產種把當與義氣幾電車書見觀東門風葉體臺萬網歷廣術號龍馬鳥魚愛聽話語頭麵邊達辦廠醫藥優嚴傷熱護續績覺讓帶給該將祇並區塊顯線簡譯誤識";

    private static final Map<String, TargetLocale> LOCALES = Map.ofEntries(
            Map.entry("zh-tw", new TargetLocale("zh-TW", "Traditional Chinese", "Traditional Han script",
                    "Use Taiwan orthography and terminology. Do not output Simplified Chinese.")),
            Map.entry("zh-hant", new TargetLocale("zh-Hant", "Traditional Chinese", "Traditional Han script",
                    "Use Traditional Chinese characters. Do not output Simplified Chinese.")),
            Map.entry("zh-cn", new TargetLocale("zh-CN", "Simplified Chinese", "Simplified Han script",
                    "Use Mainland China orthography. Do not output Traditional Chinese.")),
            Map.entry("zh-hans", new TargetLocale("zh-Hans", "Simplified Chinese", "Simplified Han script",
                    "Use Simplified Chinese characters. Do not output Traditional Chinese.")),
            Map.entry("en", new TargetLocale("en", "English", "Latin script", "Use clear idiomatic English.")),
            Map.entry("ja", new TargetLocale("ja", "Japanese", "Japanese scripts", "Use natural modern Japanese.")),
            Map.entry("ko", new TargetLocale("ko", "Korean", "Hangul", "Use natural modern Korean.")),
            Map.entry("vi", new TargetLocale("vi", "Vietnamese", "Latin script", "Use natural Vietnamese.")));

    public TargetLocale resolve(String value) {
        String raw = value == null || value.isBlank() ? "und" : value.trim();
        String key = raw.toLowerCase(Locale.ROOT).replace('_', '-');
        if ("tw".equals(key)) key = "zh-tw";
        if ("cn".equals(key)) key = "zh-cn";
        TargetLocale known = LOCALES.get(key);
        if (known != null) return known;
        Locale locale = Locale.forLanguageTag(raw);
        String canonical = locale.getLanguage().isBlank() ? raw : locale.toLanguageTag();
        String name = locale.getDisplayLanguage(Locale.ENGLISH);
        if (name == null || name.isBlank()) name = canonical;
        return new TargetLocale(canonical, name, "script appropriate for " + name,
                "Use the standard written form for locale " + canonical + ".");
    }

    public boolean accepts(String text, String targetLocale) {
        if (text == null || text.isBlank()) return false;
        String locale = resolve(targetLocale).locale().toLowerCase(Locale.ROOT);
        if (locale.equals("zh-tw") || locale.equals("zh-hant")) {
            return exclusiveCount(text, SIMPLIFIED_ONLY) <= exclusiveCount(text, TRADITIONAL_ONLY);
        }
        if (locale.equals("zh-cn") || locale.equals("zh-hans")) {
            return exclusiveCount(text, TRADITIONAL_ONLY) <= exclusiveCount(text, SIMPLIFIED_ONLY);
        }
        return true;
    }

    public boolean needsValidation(String targetLocale) {
        String locale = resolve(targetLocale).locale().toLowerCase(Locale.ROOT);
        return locale.equals("zh-tw") || locale.equals("zh-hant")
                || locale.equals("zh-cn") || locale.equals("zh-hans");
    }

    private static long exclusiveCount(String text, String characters) {
        return text.codePoints().filter(cp -> characters.indexOf(cp) >= 0).count();
    }

    public record TargetLocale(String locale, String languageName, String script, String localeRule) {
    }
}
