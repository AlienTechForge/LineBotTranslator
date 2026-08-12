package com.linetranslate.bot.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.linetranslate.bot.service.imageproxy.ImageProxyAsset;
import com.linetranslate.bot.service.imageproxy.ImageProxyContentService;

@WebMvcTest(ImageProxyController.class)
class ImageProxyControllerTests {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ImageProxyContentService contentService;

    @Test
    void servesOriginalAndPreviewAsNonsniffPngWithoutExposingUpstreamUrl() throws Exception {
        byte[] original = new byte[] { 1, 2, 3 };
        byte[] preview = new byte[] { 4, 5 };
        when(contentService.load("0123456789abcdefghij-_"))
                .thenReturn(Optional.of(new ImageProxyAsset(original, preview)));

        mockMvc.perform(get("/i/0123456789abcdefghij-_"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(original))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, private"));

        mockMvc.perform(get("/i/0123456789abcdefghij-_/preview"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(preview));
    }

    @Test
    void unknownOrMalformedTokensReturnGenericNotFound() throws Exception {
        when(contentService.load("0123456789abcdefghij-_"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/i/0123456789abcdefghij-_"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/i/not-valid"))
                .andExpect(status().isNotFound());
    }
}
