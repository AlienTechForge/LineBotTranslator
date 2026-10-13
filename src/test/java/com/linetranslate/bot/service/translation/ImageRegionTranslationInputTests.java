package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class ImageRegionTranslationInputTests {

    @Test
    void boundedTokensDropOverLongValuesAndKeepTheFirstSixtyFourInOrder() {
        List<String> prices = IntStream.rangeClosed(1, 80).mapToObj(value -> "$" + value).toList();
        List<String> derived = new ArrayList<>();
        derived.add("9".repeat(ImageRegionTranslationInput.MAX_PROTECTED_TOKEN_LENGTH + 1));
        derived.add(null);
        derived.addAll(prices);

        List<String> bounded = ImageRegionTranslationInput.boundedProtectedTokens(derived);

        assertThat(bounded).containsExactlyElementsOf(prices.subList(0, 64));
        assertThat(new ImageRegionTranslationInput("menu", "Menu", "en", bounded).protectedTokens())
                .containsExactlyElementsOf(bounded);
    }

    @Test
    void boundedTokensKeepTokensAtTheLimits() {
        String longest = "1".repeat(ImageRegionTranslationInput.MAX_PROTECTED_TOKEN_LENGTH);
        List<String> derived = new ArrayList<>(List.of(longest));
        IntStream.range(1, ImageRegionTranslationInput.MAX_PROTECTED_TOKENS).forEach(value -> derived.add("$" + value));

        assertThat(ImageRegionTranslationInput.boundedProtectedTokens(derived)).containsExactlyElementsOf(derived);
        assertThat(ImageRegionTranslationInput.boundedProtectedTokens(null)).isEmpty();
        assertThat(ImageRegionTranslationInput.boundedProtectedTokens(List.of())).isEmpty();
    }

    @Test
    void constructorStillRejectsTokensOutsideTheContract() {
        List<String> tooMany = IntStream.rangeClosed(1, ImageRegionTranslationInput.MAX_PROTECTED_TOKENS + 1)
                .mapToObj(value -> "$" + value).toList();
        String tooLong = "9".repeat(ImageRegionTranslationInput.MAX_PROTECTED_TOKEN_LENGTH + 1);

        assertThatThrownBy(() -> new ImageRegionTranslationInput("menu", "Menu", "en", tooMany))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageRegionTranslationInput("menu", "Menu", "en", List.of(tooLong)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
