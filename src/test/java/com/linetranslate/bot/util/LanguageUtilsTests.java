package com.linetranslate.bot.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

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
    void everySupportedCodeHasAChineseDisplayNameThatResolvesBackToIt() {
        // A code without a name made replies show the bare code ("hu") next to Chinese names.
        List<String> problems = new ArrayList<>();
        new TreeSet<>(LanguageUtils.languageNameToCode().values()).forEach(code -> {
            String name = LanguageUtils.toChineseName(code);
            if (name.equals(code) || !isHan(name)) {
                problems.add(code + " has no Chinese display name: " + name);
            } else if (!LanguageUtils.toLanguageCode(name).equals(code)) {
                problems.add(name + " does not resolve back to " + code);
            }
        });

        assertThat(problems).isEmpty();
    }

    @Test
    void hungarianHebrewFilipinoAndIcelandicHaveChineseNamesAndAliases() {
        Map<String, List<String>> namesByCode = new LinkedHashMap<>();
        namesByCode.put("hu", List.of("匈牙利文", "匈牙利語"));
        namesByCode.put("he", List.of("希伯來文", "希伯來語"));
        namesByCode.put("tl", List.of("菲律賓文", "菲律賓語", "他加祿文", "他加祿語"));
        namesByCode.put("is", List.of("冰島文", "冰島語"));

        namesByCode.forEach((code, names) -> {
            assertThat(LanguageUtils.toChineseName(code)).as(code).isEqualTo(names.get(0));
            assertThat(LanguageUtils.toChineseName(code.toUpperCase(Locale.ROOT)))
                    .as(code + " upper case").isEqualTo(names.get(0));
            assertThat(LanguageUtils.toLanguageCode(code)).as(code).isEqualTo(code);
            for (String name : names) {
                assertThat(LanguageUtils.isSupported(name)).as(name).isTrue();
                assertThat(LanguageUtils.toLanguageCode(name)).as(name).isEqualTo(code);
                assertThat(LanguageUtils.leadingChineseName(name + "你好")).as(name).isEqualTo(name);
            }
        });
        // The country-style shorthands for these languages keep resolving to them.
        assertThat(LanguageUtils.toLanguageCode("ph")).isEqualTo("tl");
        assertThat(LanguageUtils.toLanguageCode("il")).isEqualTo("he");
        assertThat(LanguageUtils.toLanguageCode("ice")).isEqualTo("is");
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
    void leadingChineseNameStopsAtTheEndOfTheNameWhenTextIsGluedToIt() {
        assertThat(LanguageUtils.leadingChineseName("英文我愛你")).isEqualTo("英文");
        assertThat(LanguageUtils.leadingChineseName("繁體中文")).isEqualTo("繁體中文");
        assertThat(LanguageUtils.leadingChineseName("簡體中文你好嗎")).isEqualTo("簡體中文");
        assertThat(LanguageUtils.leadingChineseName("西班牙語早安")).isEqualTo("西班牙語");
    }

    @Test
    void leadingChineseNameIsNullWhenTheTextDoesNotStartWithAKnownChineseName() {
        assertThat(LanguageUtils.leadingChineseName(null)).isNull();
        assertThat(LanguageUtils.leadingChineseName("")).isNull();
        assertThat(LanguageUtils.leadingChineseName("火星文你好")).isNull();
        assertThat(LanguageUtils.leadingChineseName("我愛你英文")).isNull();
        // Codes and Latin aliases are matched by isSupported, not by the Chinese-name lookup.
        assertThat(LanguageUtils.leadingChineseName("en")).isNull();
        assertThat(LanguageUtils.leadingChineseName("jp 你好")).isNull();
    }

    @Test
    void leadingChineseNameReturnsTheLongestKnownNameForEveryChineseName() {
        // A name that is a prefix of another must never hide it, with or without glued text.
        List<String> wrong = new ArrayList<>();
        LanguageUtils.languageNameToCode().keySet().stream()
                .filter(name -> name.codePoints().allMatch(
                        codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN))
                .forEach(name -> {
                    for (String glued : List.of(name, name + "我愛你")) {
                        String found = LanguageUtils.leadingChineseName(glued);
                        if (found == null || !found.equals(name)) {
                            wrong.add(glued + " -> " + found);
                        }
                    }
                });

        assertThat(wrong).isEmpty();
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

    private static boolean isHan(String text) {
        return !text.isEmpty() && text.codePoints()
                .allMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static String language(String languageTag) {
        return Locale.forLanguageTag(languageTag).getLanguage();
    }
}
