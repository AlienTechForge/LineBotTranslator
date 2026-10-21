package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.imagetranslator.ImageTranslatorProperties;
import com.linetranslate.bot.service.preference.UserPreferencesModule;
import com.linetranslate.bot.service.settings.RuntimeSettings;

class ImageTranslationRuntimeSettingsTests {

    private final ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
    private final UserProfileRepository repository = mock(UserProfileRepository.class);
    private final UserPreferencesModule profiles = mock(UserPreferencesModule.class);

    @Test
    void runtimeOcrSwitchStopsRequestsBeforeThePipeline() {
        RuntimeSettings disabled = new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", false,
                2, 1, null, "U-admin", RuntimeSettings.Source.PERSISTED);
        ImageTranslationService service = new ImageTranslationService(
                pipeline, repository, profiles, () -> disabled);

        assertThat(service.isOcrEnabled()).isFalse();
        assertThat(service.processImageTranslation("U-test", "message-id"))
                .isEqualTo("圖片翻譯功能目前已停用。請稍後再試。");
        verifyNoInteractions(pipeline, repository, profiles);
    }

    @Test
    void switchingTheServiceClientOffStopsRequestsBeforeThePipelineWhateverTheRuntimeSettingSays() {
        RuntimeSettings enabled = new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", true,
                2, 1, null, "U-admin", RuntimeSettings.Source.PERSISTED);
        ImageTranslatorProperties off = new ImageTranslatorProperties();
        off.setEnabled(false);
        ImageTranslationService service = new ImageTranslationService(
                pipeline, repository, profiles, () -> enabled, off);

        assertThat(service.isOcrEnabled()).isFalse();
        assertThat(service.processImageTranslation("U-test", "message-id"))
                .isEqualTo("圖片翻譯功能目前已停用。請稍後再試。");
        verifyNoInteractions(pipeline, repository, profiles);
    }

    @Test
    void withoutPersistedSettingsImageTranslationIsOn() {
        assertThat(new ImageTranslationService(pipeline, repository, profiles, () -> null).isOcrEnabled()).isTrue();
    }

    @Test
    void theTextReplyIsOffByDefaultInEveryCompatibilityConstructorOfTheSettings() {
        RuntimeSettings legacy = new RuntimeSettings("en", "zh-TW", "m", true, 2, 1, null, null,
                RuntimeSettings.Source.DEPLOYMENT_DEFAULTS);
        RuntimeSettings withShortUrl = new RuntimeSettings("en", "zh-TW", "m", true, true, 2, 1, null, null,
                RuntimeSettings.Source.DEPLOYMENT_DEFAULTS);

        assertThat(legacy.imageTextReplyEnabled()).isFalse();
        assertThat(withShortUrl.imageTextReplyEnabled()).isFalse();
    }
}
