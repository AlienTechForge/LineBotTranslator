package com.linetranslate.bot.service.line;

import org.springframework.stereotype.Service;

import com.linecorp.bot.messaging.model.Message;
import com.linetranslate.bot.logging.SafeLog;
import com.linetranslate.bot.service.line.intent.AdminIntentParser;
import com.linetranslate.bot.service.line.intent.LineIntent;
import com.linetranslate.bot.service.ocr.ImageTranslationService;
import com.linetranslate.bot.service.translation.TranslationService;
import com.linetranslate.bot.service.translation.TranslationActionModule;

import lombok.extern.slf4j.Slf4j;

/** Deep Module that executes domain intents and renders their LINE messages. */
@Service
@Slf4j
public class LineInteractionModule {

    private final TranslationService translationService;
    private final TranslationActionModule translationActionModule;
    private final LineUserProfileService lineUserProfileService;
    private final ImageTranslationService imageTranslationService;
    private final AdminIntentParser adminIntentParser;
    private final AdminInteractionModule adminInteractionModule;
    private final LineMessageRenderer renderer;

    public LineInteractionModule(
            TranslationService translationService,
            TranslationActionModule translationActionModule,
            LineUserProfileService lineUserProfileService,
            ImageTranslationService imageTranslationService,
            AdminIntentParser adminIntentParser,
            AdminInteractionModule adminInteractionModule,
            LineMessageRenderer renderer) {
        this.translationService = translationService;
        this.translationActionModule = translationActionModule;
        this.lineUserProfileService = lineUserProfileService;
        this.imageTranslationService = imageTranslationService;
        this.adminIntentParser = adminIntentParser;
        this.adminInteractionModule = adminInteractionModule;
        this.renderer = renderer;
    }

    public Message execute(String userId, LineIntent intent) {
        if (intent instanceof LineIntent.TranslateText translation) {
            return renderer.translation(
                    translationService.processTranslationResponse(userId, translation.text()));
        }
        if (intent instanceof LineIntent.QuickTranslate translation) {
            return renderer.translation(
                    translationService.quickTranslateResponse(
                            userId, translation.text(), translation.language()));
        }
        if (intent instanceof LineIntent.Retranslate translation) {
            return renderer.translation(translationActionModule.execute(
                    userId, translation.recordId(), translation.targetLanguage()));
        }
        if (intent instanceof LineIntent.UserCommand command) {
            return executeUserCommand(userId, command);
        }
        if (intent instanceof LineIntent.AdminCommand command) {
            return adminInteractionModule.execute(userId, adminIntentParser.parse(command.command()));
        }
        return renderer.invalid((LineIntent.Invalid) intent);
    }

    public Message executeImage(String userId, String messageId) {
        try {
            return renderer.imageResult(
                    imageTranslationService.processImageTranslationResponse(userId, messageId));
        } catch (Exception exception) {
            log.error("圖片翻譯處理失敗: user={}, failure={}",
                    SafeLog.user(userId), SafeLog.failure(exception));
            return renderer.imageFailure();
        }
    }

    private Message executeUserCommand(String userId, LineIntent.UserCommand command) {
        return switch (command.action()) {
            case HELP -> renderer.help();
            case ABOUT -> renderer.about();
            case SET_MODEL -> renderer.settingResult(
                    translationService.setPreferredModel(userId, command.argument()));
            case MODELS -> renderer.models(command.argument());
            case SET_FOREIGN_LANGUAGE -> renderer.settingResult(
                    translationService.setPreferredLanguage(userId, command.argument()));
            case PROFILE -> renderer.profile(lineUserProfileService.getUserProfileInfo(userId));
            case STATUS -> renderer.status(translationService.getUserStatus(userId));
            case LANGUAGE_MENU -> renderer.languageSelection();
            case SET_CHINESE_LANGUAGE -> renderer.settingResult(
                    translationService.setPreferredChineseTargetLanguage(userId, command.argument()));
        };
    }
}
