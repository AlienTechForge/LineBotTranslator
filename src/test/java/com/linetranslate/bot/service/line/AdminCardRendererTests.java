package com.linetranslate.bot.service.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.linecorp.bot.jackson.ModelObjectMapper;
import com.linecorp.bot.messaging.model.FlexBubble;
import com.linecorp.bot.messaging.model.FlexButton;
import com.linecorp.bot.messaging.model.FlexMessage;
import com.linecorp.bot.messaging.model.Message;
import com.linecorp.bot.messaging.model.MessageAction;
import com.linecorp.bot.messaging.model.TextMessage;

class AdminCardRendererTests {

    private final AdminCardRenderer renderer = new AdminCardRenderer();

    @Test
    void dashboardProvidesWhitelistedAdminNavigationActions() {
        Message rendered = renderer.dashboard();

        assertThat(rendered).isInstanceOf(FlexMessage.class);
        FlexMessage message = (FlexMessage) rendered;
        FlexBubble bubble = (FlexBubble) message.contents();
        assertThat(message.altText()).contains("管理員控制台").hasSizeLessThanOrEqualTo(1500);
        assertThat(bubble.footer().contents())
                .allSatisfy(component -> {
                    assertThat(component).isInstanceOf(FlexButton.class);
                    assertThat(((FlexButton) component).action()).isInstanceOf(MessageAction.class);
                    assertThat(((MessageAction) ((FlexButton) component).action()).text())
                            .isIn("/admin stats", "/admin today", "/admin users",
                                    "/admin config", "/admin usage", "/admin usage summary");
                });
    }

    @Test
    void cardSerializesWithinLineBubbleLimit() throws Exception {
        Message rendered = renderer.info("系統統計", "翻譯總數：42\n活躍使用者：7");

        assertThat(rendered).isInstanceOf(FlexMessage.class);
        byte[] json = ModelObjectMapper.createNewObjectMapper().writeValueAsBytes(rendered);
        assertThat(json.length).isLessThanOrEqualTo(30 * 1024);
    }

    @Test
    void oversizedContentFallsBackToBoundedTextMessage() {
        Message rendered = renderer.info("大型報表", "資".repeat(20_000));

        assertThat(rendered).isInstanceOfSatisfying(
                TextMessage.class,
                message -> {
                    assertThat(message.text()).hasSizeLessThanOrEqualTo(5000);
                    assertThat(message.text()).contains("內容過長");
                    assertThat(message.text().getBytes(StandardCharsets.UTF_8).length).isPositive();
                });
    }

    @Test
    void arbitraryCommandsCannotBeEmbeddedInAdminCards() {
        AdminCardRenderer.AdminCardAction unsafe =
                new AdminCardRenderer.AdminCardAction("廣播 secret", "/admin broadcast secret");

        assertThatThrownBy(() -> renderer.card("危險操作", "不得送出", List.of(unsafe)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
    }
}
