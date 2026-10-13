package com.linetranslate.bot.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LanguageUtilsTests {

    /**
     * Aliases that deliberately resolve an ISO 639-1 code to another language. "no" and "nb" are
     * both Norwegian. The others are country shorthands kept on purpose; the language each one
     * shadows (Twi, Kanuri, Breton, Sango) is not one the bot offers. Anything else that shadows a
     * language code makes a user who types that code get a different language.
     */
    private static final Map<String, String> DELIBERATE_SHADOWING = Map.of(
            "no", "nb",
            "tw", "zh-TW",
            "kr", "ko",
            "br", "pt",
            "sg", "zh-CN");

    @Test
    void regionLanguageCodesAreCanonicalizedCaseInsensitively() {
        assertThat(LanguageUtils.isSupported("zh-TW")).isTrue();
        assertThat(LanguageUtils.isSupported("zh-tw")).isTrue();
        assertThat(LanguageUtils.toLanguageCode("ZH-tW")).isEqualTo("zh-TW");
        assertThat(LanguageUtils.toChineseName("zh-tw")).isEqualTo("繁體中文");
    }

    @Test
    void everyIsoLanguageCodeResolvesToItsOwnLanguage() {
        List<String> shadowed = new ArrayList<>();
        for (String code : Locale.getISOLanguages()) {
            String resolved = LanguageUtils.toLanguageCode(code);
            boolean ownLanguage = resolved.equalsIgnoreCase(code)
                    || language(resolved).equals(language(code));
            if (!ownLanguage && !resolved.equals(DELIBERATE_SHADOWING.get(code))) {
                shadowed.add(code + " -> " + resolved);
            }
        }

        assertThat(shadowed).isEmpty();
        DELIBERATE_SHADOWING.forEach((code, expected) ->
                assertThat(LanguageUtils.toLanguageCode(code)).as(code).isEqualTo(expected));
    }

    @Test
    void everyMappedCodeIsItselfASupportedCanonicalCode() {
        // Callers validate both the raw input and toLanguageCode(input), so a value the table
        // produces must pass isSupported and must not normalize any further.
        List<String> unsupported = new ArrayList<>();
        LanguageUtils.languageNameToCode().forEach((name, code) -> {
            if (!LanguageUtils.isSupported(code) || !LanguageUtils.toLanguageCode(code).equals(code)) {
                unsupported.add(name + " -> " + code);
            }
        });

        assertThat(unsupported).isEmpty();
    }

    @Test
    void norwegianAndUkrainianAliasesResolveToSupportedLanguages() {
        assertThat(LanguageUtils.toLanguageCode("no")).isEqualTo("nb");
        assertThat(LanguageUtils.toLanguageCode("nor")).isEqualTo("nb");
        assertThat(LanguageUtils.toLanguageCode("ua")).isEqualTo("uk");
        assertThat(LanguageUtils.toLanguageCode("ukr")).isEqualTo("uk");
        assertThat(LanguageUtils.isSupported("nb")).isTrue();
        assertThat(LanguageUtils.isSupported("uk")).isTrue();
        assertThat(LanguageUtils.toChineseName("nb")).isEqualTo("挪威文");
        assertThat(LanguageUtils.toChineseName("uk")).isEqualTo("烏克蘭文");
    }

    @Test
    void languageCodesThatAreAlsoCountryCodesKeepTheirLanguage() {
        // Arabic, Catalan, Belarusian and Burmese, not Argentina, Canada, Belgium and Malaysia.
        assertThat(LanguageUtils.toLanguageCode("ar")).isEqualTo("ar");
        assertThat(LanguageUtils.toLanguageCode("AR")).isEqualTo("ar");
        assertThat(LanguageUtils.toLanguageCode("ca")).isEqualTo("ca");
        assertThat(LanguageUtils.toLanguageCode("be")).isEqualTo("be");
        assertThat(LanguageUtils.toLanguageCode("my")).isEqualTo("my");

        assertThat(LanguageUtils.isSupported("ar")).isTrue();
        assertThat(LanguageUtils.isSupported("ca")).isFalse();
        assertThat(LanguageUtils.isSupported("be")).isFalse();
        assertThat(LanguageUtils.isSupported("my")).isFalse();
    }

    @Test
    void countryShorthandsThatAreNotLanguageCodesStillResolve() {
        assertThat(LanguageUtils.toLanguageCode("jp")).isEqualTo("ja");
        assertThat(LanguageUtils.toLanguageCode("cn")).isEqualTo("zh-CN");
        assertThat(LanguageUtils.toLanguageCode("hk")).isEqualTo("zh-TW");
        assertThat(LanguageUtils.toLanguageCode("us")).isEqualTo("en");
        assertThat(LanguageUtils.toLanguageCode("ua")).isEqualTo("uk");
        assertThat(LanguageUtils.toLanguageCode("arg")).isEqualTo("es");
        assertThat(LanguageUtils.toLanguageCode("can")).isEqualTo("en");
    }

    @Test
    void aliasTableRejectsAKeyReassignedToADifferentCode() {
        // The real table is built through this map, so loading LanguageUtils at all proves it has
        // no conflicting entry.
        Map<String, String> aliases = new LanguageUtils.ConflictRejectingMap();
        aliases.put("br", "pt");
        aliases.put("br", "pt");
        aliases.put("ar", "ar");

        assertThatThrownBy(() -> aliases.put("ar", "es"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(aliases).containsEntry("ar", "ar").containsEntry("br", "pt");
    }

    private static String language(String languageTag) {
        return Locale.forLanguageTag(languageTag).getLanguage();
    }
}
