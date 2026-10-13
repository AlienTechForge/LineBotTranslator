package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TargetLocalePolicyTests {

    private final TargetLocalePolicy policy = new TargetLocalePolicy();

    @Test
    void canonicalizesChineseLocalesAndRejectsClearlySimplifiedOutputForTaiwan() {
        assertThat(policy.resolve("zh-tw").locale()).isEqualTo("zh-TW");
        assertThat(policy.resolve("zh").locale()).isEqualTo("zh-TW");
        assertThat(policy.accepts("保護自己免受熱傷害", "zh-TW")).isTrue();
        assertThat(policy.accepts("保护自己免受热伤害", "zh-TW")).isFalse();
        assertThat(policy.accepts(
                "面对针对自己的抗议集会，赖清德总统回击说，如果要抗议独裁，就去天安门。",
                "zh-TW")).isFalse();
    }

    @Test
    void acceptsShortTraditionalTextBuiltFromCharactersSharedWithSimplified() {
        for (String target : new String[] {"zh-TW", "zh-Hant"}) {
            assertThat(policy.accepts("台灣", target)).as(target).isTrue();
            assertThat(policy.accepts("我只想去台北", target)).as(target).isTrue();
            assertThat(policy.accepts("你好，我只是想問一下。", target)).as(target).isTrue();
            assertThat(policy.accepts("這個方面需要處理", target)).as(target).isTrue();
            assertThat(policy.accepts("距離三公里", target)).as(target).isTrue();
            assertThat(policy.accepts("皇后", target)).as(target).isTrue();
            assertThat(policy.accepts("客廳的茶几", target)).as(target).isTrue();
            assertThat(policy.accepts("这是简体中文的说明", target)).as(target).isFalse();
        }
    }

    @Test
    void acceptsShortSimplifiedTextBuiltFromCharactersSharedWithTraditional() {
        for (String target : new String[] {"zh-CN", "zh-Hans"}) {
            assertThat(policy.accepts("台湾", target)).as(target).isTrue();
            assertThat(policy.accepts("我只想去台北", target)).as(target).isTrue();
            assertThat(policy.accepts("你好，我只是想问一下。", target)).as(target).isTrue();
            assertThat(policy.accepts("这个方面需要处理", target)).as(target).isTrue();
            assertThat(policy.accepts("古希腊的神祇", target)).as(target).isTrue();
            assertThat(policy.accepts("這是繁體中文的說明", target)).as(target).isFalse();
        }
    }

    @Test
    void acceptsNeutralHanTextAndDoesNotApplyChineseRulesToOtherLanguages() {
        assertThat(policy.accepts("你好", "zh-TW")).isTrue();
        assertThat(policy.accepts("translation", "en")).isTrue();
    }
}
