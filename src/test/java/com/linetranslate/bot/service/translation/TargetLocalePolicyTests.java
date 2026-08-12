package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TargetLocalePolicyTests {

    private final TargetLocalePolicy policy = new TargetLocalePolicy();

    @Test
    void canonicalizesChineseLocalesAndRejectsClearlySimplifiedOutputForTaiwan() {
        assertThat(policy.resolve("zh-tw").locale()).isEqualTo("zh-TW");
        assertThat(policy.accepts("保護自己免受熱傷害", "zh-TW")).isTrue();
        assertThat(policy.accepts("保护自己免受热伤害", "zh-TW")).isFalse();
    }

    @Test
    void acceptsNeutralHanTextAndDoesNotApplyChineseRulesToOtherLanguages() {
        assertThat(policy.accepts("你好", "zh-TW")).isTrue();
        assertThat(policy.accepts("translation", "en")).isTrue();
    }
}
