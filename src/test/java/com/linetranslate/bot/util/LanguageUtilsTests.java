package com.linetranslate.bot.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LanguageUtilsTests {

    @Test
    void regionLanguageCodesAreCanonicalizedCaseInsensitively() {
        assertThat(LanguageUtils.isSupported("zh-TW")).isTrue();
        assertThat(LanguageUtils.isSupported("zh-tw")).isTrue();
        assertThat(LanguageUtils.toLanguageCode("ZH-tW")).isEqualTo("zh-TW");
        assertThat(LanguageUtils.toChineseName("zh-tw")).isEqualTo("繁體中文");
    }
}
