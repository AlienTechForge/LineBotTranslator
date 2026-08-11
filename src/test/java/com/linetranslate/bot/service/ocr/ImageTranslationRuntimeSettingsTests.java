package com.linetranslate.bot.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import com.linetranslate.bot.repository.UserProfileRepository;
import com.linetranslate.bot.service.settings.RuntimeSettings;

class ImageTranslationRuntimeSettingsTests {

    @Test
    void runtimeOcrSwitchStopsRequestsBeforeThePipeline() {
        ImageTranslationPipeline pipeline = mock(ImageTranslationPipeline.class);
        UserProfileRepository repository = mock(UserProfileRepository.class);
        RuntimeSettings disabled = new RuntimeSettings(
                "en", "zh-TW", "openai/gpt-4o-mini", false,
                2, 1, null, "U-admin", RuntimeSettings.Source.PERSISTED);
        ImageTranslationService service = new ImageTranslationService(
                pipeline, repository, () -> disabled);

        assertThat(service.processImageTranslation("U-test", "message-id"))
                .isEqualTo("OCR 功能目前已停用。請稍後再試。");
        verifyNoInteractions(pipeline, repository);
    }
}
