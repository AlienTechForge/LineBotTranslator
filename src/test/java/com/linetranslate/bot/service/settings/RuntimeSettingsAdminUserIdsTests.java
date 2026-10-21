package com.linetranslate.bot.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class RuntimeSettingsAdminUserIdsTests {

    @Test
    void adminUserIdsAreTrimmedDeduplicatedOrderedAndUnmodifiable() {
        List<String> source = new ArrayList<>(
                Arrays.asList(" U-b ", "U-a", null, "", "  ", "U-b", "U-c", "U-a"));

        RuntimeSettings settings = settingsWith(source);
        source.add("U-late");

        assertThat(settings.adminUserIds()).containsExactly("U-b", "U-a", "U-c");
        assertThat(settings.adminUserIds().contains(null)).isFalse();
        assertThatThrownBy(() -> settings.adminUserIds().add("U-x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void absentListBecomesEmpty() {
        assertThat(settingsWith(null).adminUserIds()).isEmpty();
    }

    @Test
    void compatibilityConstructorsDefaultToNoPersistedAdministrators() {
        RuntimeSettings withShortUrl = new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, true, 3, 2, null, null,
                RuntimeSettings.Source.PERSISTED);
        RuntimeSettings original = new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, 1, 0, null, null,
                RuntimeSettings.Source.DEPLOYMENT_DEFAULTS);

        RuntimeSettings withImageTextReply = new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, true, false, 4, 2, null, null,
                RuntimeSettings.Source.PERSISTED);

        assertThat(withShortUrl.adminUserIds()).isEmpty();
        assertThat(withShortUrl.shortUrlEnabled()).isTrue();
        assertThat(withShortUrl.imageTextReplyEnabled()).isFalse();
        assertThat(withImageTextReply.adminUserIds()).isEmpty();
        assertThat(withImageTextReply.imageTextReplyEnabled()).isFalse();
        assertThat(original.adminUserIds()).isEmpty();
        assertThat(original.shortUrlEnabled()).isFalse();
        // Picture-only replies: the text reply is off unless it is asked for.
        assertThat(original.imageTextReplyEnabled()).isFalse();
    }

    @Test
    void textualFormNeverShowsUserIdentifiers() {
        RuntimeSettings settings = new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, false, true,
                List.of("U-secret-admin-one", "U-secret-admin-two"), 4, 1, null,
                "U-secret-operator", RuntimeSettings.Source.PERSISTED);

        assertThat(settings.toString())
                .contains("adminUserCount=2")
                .doesNotContain("U-secret-admin-one", "U-secret-admin-two", "U-secret-operator");
    }

    private static RuntimeSettings settingsWith(List<String> adminUserIds) {
        return new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true, false, true, adminUserIds, 4, 0, null, null,
                RuntimeSettings.Source.PERSISTED);
    }
}
