package com.linetranslate.bot.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.linetranslate.bot.service.storage.MinioObjectBrowser;

/**
 * A deployment without {@code ADMIN_WEB_TOKEN} exposes nothing: the routes are not merely
 * unauthorized, they are not registered.
 */
@WebMvcTest(AdminImageGalleryController.class)
@TestPropertySource(properties = "app.admin.web.token=")
class AdminImageGalleryBlankTokenTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MinioObjectBrowser browser;

    @Test
    void noRouteIsRegisteredEvenForACallerWithAnAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/admin/images")).andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/images/api/list").header(HttpHeaders.AUTHORIZATION, "Bearer anything"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/images/api/list").header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/images/api/url").param("name", "images/a.jpg"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/images/api/object").param("name", "images/a.jpg"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/images/api/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[\"images/a.jpg\"]}"))
                .andExpect(status().isNotFound());
    }
}
