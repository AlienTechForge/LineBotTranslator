package com.linetranslate.bot.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linetranslate.bot.model.UserProfile;
import com.linetranslate.bot.service.ai.AiExecutionResult;
import com.linetranslate.bot.service.preference.UserPreferencesModule;

/** Pins which part of a 翻譯成 message becomes the source text and which becomes the target. */
@ExtendWith(MockitoExtension.class)
class TranslationCommandParsingTests {

    private static final String USER_ID = "U-command-parsing";

    @Mock
    private TranslationWorkflowModule translationWorkflowModule;
    @Mock
    private UserPreferencesModule userPreferencesModule;

    private TranslationService translationService;

    @BeforeEach
    void setUp() {
        when(userPreferencesModule.profile(USER_ID))
                .thenReturn(UserProfile.builder().userId(USER_ID).build());
        when(translationWorkflowModule.execute(any(TranslationWorkflowRequest.class)))
                .thenAnswer(invocation -> {
                    TranslationWorkflowRequest request = invocation.getArgument(0);
                    return new TranslationWorkflowOutcome.Success(new TranslationWorkflowResult(
                            request.sourceText(),
                            "zh-TW",
                            request.requestedTargetLanguage() == null
                                    ? "en"
                                    : request.requestedTargetLanguage(),
                            new AiExecutionResult("translated", "openrouter", "test-model"),
                            1L,
                            request.kind()));
                });
        translationService = new TranslationService(translationWorkflowModule, userPreferencesModule);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("explicitTargetRequests")
    void explicitCommandKeepsEveryLineOfTheSourceText(
            String description, String message, String expectedSource, String expectedTarget) {
        translationService.processTranslationResponse(USER_ID, message);

        TranslationWorkflowRequest request = capturedRequest();
        assertThat(request.sourceText()).isEqualTo(expectedSource);
        assertThat(request.requestedTargetLanguage()).isEqualTo(expectedTarget);
        assertThat(request.kind()).isEqualTo(TranslationRequestKind.STANDARD_TEXT);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("plainTextRequests")
    void textThatOnlyMentionsTheCommandIsTranslatedAsPlainText(String description, String message) {
        translationService.processTranslationResponse(USER_ID, message);

        TranslationWorkflowRequest request = capturedRequest();
        assertThat(request.sourceText()).isEqualTo(message);
        assertThat(request.requestedTargetLanguage()).isNull();
    }

    private TranslationWorkflowRequest capturedRequest() {
        ArgumentCaptor<TranslationWorkflowRequest> captor =
                ArgumentCaptor.forClass(TranslationWorkflowRequest.class);
        verify(translationWorkflowModule).execute(captor.capture());
        return captor.getValue();
    }

    private static Stream<Arguments> explicitTargetRequests() {
        return Stream.of(
                Arguments.of("three lines, command line with Chinese name",
                        "第一行\n第二行\n第三行\n翻譯成日文", "第一行\n第二行\n第三行", "ja"),
                Arguments.of("three lines, command line with language code",
                        "first line\nsecond line\nthird line\n翻譯成ja",
                        "first line\nsecond line\nthird line", "ja"),
                Arguments.of("two lines, command at the end of the last line (Chinese name)",
                        "line one\nline two 翻譯成日文", "line one\nline two", "ja"),
                Arguments.of("two lines, command at the end of the last line (language code)",
                        "第一行\n第二行 翻譯成ko", "第一行\n第二行", "ko"),
                Arguments.of("text after the command line is kept",
                        "第一行\n翻譯成日文\n第二行\n第三行", "第一行 第二行\n第三行", "ja"),
                Arguments.of("prefix command followed by several lines",
                        "翻譯成日文\n第一行\n第二行", "第一行\n第二行", "ja"),
                Arguments.of("prefix form with Chinese name, single line",
                        "翻譯成日文 你好", "你好", "ja"),
                Arguments.of("prefix form with language code, single line",
                        "翻譯成ja 你好", "你好", "ja"),
                Arguments.of("suffix form, single line",
                        "你好 翻譯成日文", "你好", "ja"),
                Arguments.of("multiline form, two lines",
                        "你好\n翻譯成ja", "你好", "ja"),
                Arguments.of("prefix form, text glued to the language name",
                        "翻譯成英文我愛你", "我愛你", "en"),
                Arguments.of("prefix form, Latin text glued to a longer language name",
                        "翻譯成繁體中文hello", "hello", "zh-TW"),
                Arguments.of("prefix form, simplified Chinese name with glued text",
                        "翻譯成簡體中文你好嗎", "你好嗎", "zh-CN"),
                Arguments.of("prefix form, four-character name with glued text",
                        "翻譯成西班牙文早安", "早安", "es"),
                Arguments.of("prefix form, alias name with glued text",
                        "翻譯成英語我愛你", "我愛你", "en"),
                Arguments.of("prefix form, glued text followed by a space and more text",
                        "翻譯成日文你好 世界", "你好 世界", "ja"),
                Arguments.of("prefix form, glued text continues on the next lines",
                        "翻譯成日文你好\n再見", "你好\n再見", "ja"),
                Arguments.of("prefix form, name followed by a space",
                        "翻譯成英文 我愛你", "我愛你", "en"),
                Arguments.of("prefix form, longer name followed by a space",
                        "翻譯成繁體中文 hello", "hello", "zh-TW"),
                Arguments.of("prefix form, name followed by a newline",
                        "翻譯成西班牙文\n你好", "你好", "es"),
                Arguments.of("suffix form, longer name alone",
                        "我愛你 翻譯成繁體中文", "我愛你", "zh-TW"),
                Arguments.of("suffix form, text glued to the language name is kept",
                        "早安 翻譯成英文謝謝", "早安 謝謝", "en"),
                Arguments.of("suffix form, longer name with glued text",
                        "good morning 翻譯成簡體中文謝謝", "good morning 謝謝", "zh-CN"),
                Arguments.of("multiline form, longer name alone",
                        "hello\n翻譯成繁體中文", "hello", "zh-TW"),
                Arguments.of("multiline form, text glued to the language name is kept",
                        "早安\n翻譯成日文謝謝", "早安 謝謝", "ja"),
                Arguments.of("multiline form, glued text then more lines",
                        "早安\n翻譯成日文謝謝\n再見", "早安 謝謝\n再見", "ja"));
    }

    private static Stream<Arguments> plainTextRequests() {
        return Stream.of(
                Arguments.of("command words inside a sentence",
                        "我想把這句話翻譯成日文可以嗎"),
                Arguments.of("command words inside the last of several lines",
                        "第一行\n第二行\n我想把這句話翻譯成日文可以嗎"),
                Arguments.of("command words followed by a space instead of a language",
                        "Please 翻譯成 English for me"),
                Arguments.of("unknown language name glued to text",
                        "翻譯成火星文我愛你"),
                Arguments.of("known language name that does not start the run",
                        "翻譯成我愛你英文"),
                Arguments.of("unknown language name after text",
                        "我愛你 翻譯成火星文謝謝"),
                Arguments.of("unknown language name on the command line of a multiline message",
                        "我愛你\n翻譯成火星文謝謝"));
    }
}
