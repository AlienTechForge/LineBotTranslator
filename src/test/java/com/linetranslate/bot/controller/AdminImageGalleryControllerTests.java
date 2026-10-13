package com.linetranslate.bot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.linetranslate.bot.service.storage.MinioObjectBrowser;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.DeleteOutcome;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Entry;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.InvalidRequestException;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Kind;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Page;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.Query;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.StorageFailureException;
import com.linetranslate.bot.service.storage.MinioObjectBrowser.StorageUnavailableException;

@WebMvcTest(AdminImageGalleryController.class)
@TestPropertySource(properties = {
        "app.admin.web.token=" + AdminImageGalleryControllerTests.TOKEN,
        "minio.bucket-name=line-bot"
})
class AdminImageGalleryControllerTests {

    static final String TOKEN = "correct-horse-battery-staple-0123456789";
    private static final String BEARER = "Bearer " + TOKEN;
    private static final String ORIGINAL = "images/00000000-0000-4000-8000-00000000000a.jpg";
    private static final String TRANSLATED = "translated-images/1760000000/00000000-0000-4000-8000-00000000000c.png";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MinioObjectBrowser browser;

    // ---- authentication -------------------------------------------------------------------

    static Stream<Arguments> guardedRequests() {
        return Stream.of(
                Arguments.of("GET", "/admin/images/api/list"),
                Arguments.of("GET", "/admin/images/api/url?name=" + ORIGINAL),
                Arguments.of("DELETE", "/admin/images/api/object?name=" + ORIGINAL),
                Arguments.of("POST", "/admin/images/api/delete"),
                Arguments.of("GET", "/admin/images/api/unknown"),
                Arguments.of("GET", "/admin/images/anything-else"));
    }

    private static MockHttpServletRequestBuilder request(String method, String uri) {
        MockHttpServletRequestBuilder builder = switch (method) {
            case "GET" -> get(uri);
            case "DELETE" -> delete(uri);
            default -> post(uri).contentType(MediaType.APPLICATION_JSON).content("{\"names\":[\"" + ORIGINAL + "\"]}");
        };
        return builder;
    }

    @ParameterizedTest
    @MethodSource("guardedRequests")
    void everyRouteExceptThePageRejectsRequestsWithoutAToken(String method, String uri) throws Exception {
        mockMvc.perform(request(method, uri))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"admin-images\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("{\"error\":\"unauthorized\"}"));

        verifyNoInteractions(browser);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Bearer wrong-token",
            "Bearer ",
            "Bearer",
            "Basic " + TOKEN,
            TOKEN,
            "Bearer " + TOKEN + "x",
            "Bearer x" + TOKEN,
            "Bearer orrect-horse-battery-staple-0123456789",
            "Token " + TOKEN
    })
    void rejectsWrongOrMalformedCredentials(String authorization) throws Exception {
        for (Arguments route : guardedRequests().toList()) {
            mockMvc.perform(request((String) route.get()[0], (String) route.get()[1])
                            .header(HttpHeaders.AUTHORIZATION, authorization))
                    .andExpect(status().isUnauthorized());
        }

        verifyNoInteractions(browser);
    }

    @Test
    void aTokenInTheUrlIsNeverAccepted() throws Exception {
        mockMvc.perform(get("/admin/images/api/list").param("token", TOKEN).param("access_token", TOKEN))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(browser);
    }

    @Test
    void authenticationIsCheckedBeforeTheBodyIsParsed() throws Exception {
        mockMvc.perform(post("/admin/images/api/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSchemeIsCaseInsensitiveAndSurroundingSpacesAreIgnored() throws Exception {
        when(browser.list(any(Query.class))).thenReturn(new Page(List.of(), null));

        mockMvc.perform(get("/admin/images/api/list").header(HttpHeaders.AUTHORIZATION, "bearer " + TOKEN + " "))
                .andExpect(status().isOk());
    }

    // ---- page -----------------------------------------------------------------------------

    @Test
    void thePageIsAStaticShellServedWithoutDataAndWithAStrictPolicy() throws Exception {
        String html = mockMvc.perform(get("/admin/images"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andReturn().getResponse().getContentAsString();

        // The shell never contains the token and talks to no third party.
        assertThat(html).contains("圖片管理", "刪除選取", "載入更多", "sessionStorage", "Authorization")
                .doesNotContain(TOKEN)
                .doesNotContainPattern("(?i)(src|href)=\"https?://")
                .doesNotContain("innerHTML", "document.write", "eval(");
        verifyNoInteractions(browser);
    }

    @Test
    void thePageTemplateIsNotServedAsAPublicStaticResource() throws Exception {
        mockMvc.perform(get("/admin/image-gallery.html")).andExpect(status().isNotFound());
        mockMvc.perform(get("/image-gallery.html")).andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/image-gallery.html").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePageNonceIsFreshPerRequestAndMatchesThePolicy() throws Exception {
        List<String> nonces = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var response = mockMvc.perform(get("/admin/images")).andExpect(status().isOk())
                    .andReturn().getResponse();
            String policy = response.getHeader("Content-Security-Policy");
            Matcher fromPolicy = Pattern.compile("script-src 'nonce-([^']+)'").matcher(policy);
            assertThat(fromPolicy.find()).isTrue();
            String nonce = fromPolicy.group(1);
            String html = response.getContentAsString();
            assertThat(html).contains("<script nonce=\"" + nonce + "\">", "<style nonce=\"" + nonce + "\">")
                    .doesNotContain("__CSP_NONCE__");
            assertThat(policy).contains("default-src 'none'", "style-src 'nonce-" + nonce + "'",
                    "connect-src 'self'", "frame-ancestors 'none'", "form-action 'none'", "base-uri 'none'")
                    .doesNotContain("unsafe-inline", "unsafe-eval");
            nonces.add(nonce);
        }
        assertThat(nonces.get(0)).isNotEqualTo(nonces.get(1));
    }

    // ---- list -----------------------------------------------------------------------------

    @Test
    void listReturnsTheBrowserPageAsJson() throws Exception {
        when(browser.list(any(Query.class))).thenReturn(new Page(
                List.of(
                        new Entry(TRANSLATED, Kind.TRANSLATED, 2048, Instant.parse("2026-10-12T08:30:00.123Z"), "image/png"),
                        new Entry(ORIGINAL, Kind.ORIGINAL, 100, Instant.parse("2026-10-11T00:00:00Z"), "image/jpeg")),
                "next-cursor"));

        mockMvc.perform(get("/admin/images/api/list").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value(TRANSLATED))
                .andExpect(jsonPath("$.items[0].kind").value("translated"))
                .andExpect(jsonPath("$.items[0].size").value(2048))
                .andExpect(jsonPath("$.items[0].lastModified").value("2026-10-12T08:30:00.123Z"))
                .andExpect(jsonPath("$.items[0].contentType").value("image/png"))
                .andExpect(jsonPath("$.items[1].kind").value("original"))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"));
    }

    @Test
    void listDefaultsToEveryKindFiftyItemsAndNoDateRange() throws Exception {
        when(browser.list(any(Query.class))).thenReturn(new Page(List.of(), null));

        mockMvc.perform(get("/admin/images/api/list").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(browser).list(query.capture());
        assertThat(query.getValue()).isEqualTo(new Query(null, null, null, 50, null));
    }

    @Test
    void listPassesKindLimitCursorAndAnInclusiveCalendarRangeInTheCallersZone() throws Exception {
        when(browser.list(any(Query.class))).thenReturn(new Page(List.of(), null));

        mockMvc.perform(get("/admin/images/api/list")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .param("kind", "translated")
                        .param("from", "2026-10-01")
                        .param("to", "2026-10-02")
                        .param("tz", "Asia/Taipei")
                        .param("limit", "25")
                        .param("cursor", "abc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(browser).list(query.capture());
        assertThat(query.getValue()).isEqualTo(new Query(
                Kind.TRANSLATED,
                Instant.parse("2026-09-30T16:00:00Z"),
                Instant.parse("2026-10-02T16:00:00Z"),
                25,
                "abc"));
    }

    @Test
    void listTreatsDatesAsUtcWhenNoZoneIsGivenAndBlankValuesAsAbsent() throws Exception {
        when(browser.list(any(Query.class))).thenReturn(new Page(List.of(), null));

        mockMvc.perform(get("/admin/images/api/list")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .param("kind", "original")
                        .param("from", "2026-10-13")
                        .param("to", "")
                        .param("tz", "")
                        .param("cursor", " "))
                .andExpect(status().isOk());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(browser).list(query.capture());
        assertThat(query.getValue()).isEqualTo(
                new Query(Kind.ORIGINAL, Instant.parse("2026-10-13T00:00:00Z"), null, 50, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "kind=videos",
            "kind=ORIGINAL",
            "from=yesterday",
            "from=2026-13-01",
            "to=2026-10-1",
            "tz=Mars/Olympus",
            "limit=0",
            "limit=201",
            "limit=many",
            "from=2026-10-05&to=2026-10-04"
    })
    void listRejectsInvalidParameters(String queryString) throws Exception {
        mockMvc.perform(get("/admin/images/api/list?" + queryString).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        verify(browser, never()).list(any());
    }

    @Test
    void listMapsBrowserFailuresToGenericErrors() throws Exception {
        when(browser.list(any(Query.class)))
                .thenThrow(new InvalidRequestException())
                .thenThrow(new StorageUnavailableException())
                .thenThrow(new StorageFailureException());

        mockMvc.perform(get("/admin/images/api/list").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        mockMvc.perform(get("/admin/images/api/list").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("storage_unavailable"));
        mockMvc.perform(get("/admin/images/api/list").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("storage_error"));
    }

    // ---- preview url ----------------------------------------------------------------------

    @Test
    void urlReturnsAShortLivedPresignedLink() throws Exception {
        when(browser.previewUrl(ORIGINAL)).thenReturn("https://s3.example.com/line-bot/" + ORIGINAL + "?X-Amz-Signature=abc");

        mockMvc.perform(get("/admin/images/api/url").param("name", ORIGINAL).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.url").value("https://s3.example.com/line-bot/" + ORIGINAL + "?X-Amz-Signature=abc"))
                .andExpect(jsonPath("$.expiresInSeconds").value(600));
    }

    @Test
    void theSignedUrlNeverAppearsInTheResponseObjectsStringForm() {
        var response = new AdminImageGalleryController.UrlResponse("https://s3.example.com/a.jpg?X-Amz-Signature=abc", 600);

        assertThat(response.toString()).doesNotContain("s3.example.com", "X-Amz-Signature", "abc");
    }

    @Test
    void urlRejectsAMissingOrUnmanagedName() throws Exception {
        when(browser.previewUrl(null)).thenThrow(new InvalidRequestException());
        when(browser.previewUrl("secrets/linebot.json")).thenThrow(new InvalidRequestException());

        mockMvc.perform(get("/admin/images/api/url").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/images/api/url").param("name", "secrets/linebot.json")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    // ---- delete ---------------------------------------------------------------------------

    @Test
    void deleteOneRemovesTheNamedObject() throws Exception {
        when(browser.delete(List.of(TRANSLATED))).thenReturn(new DeleteOutcome(1, List.of()));

        mockMvc.perform(delete("/admin/images/api/object").param("name", TRANSLATED)
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1))
                .andExpect(jsonPath("$.failed.length()").value(0));
    }

    @Test
    void deleteOneRefusesNamesOutsideTheManagedPrefixes() throws Exception {
        when(browser.delete(List.of("../linebot.json"))).thenThrow(new InvalidRequestException());

        mockMvc.perform(delete("/admin/images/api/object").param("name", "../linebot.json")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        mockMvc.perform(delete("/admin/images/api/object").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteOneReportsAStorageFailureAsBadGateway() throws Exception {
        when(browser.delete(List.of(ORIGINAL))).thenReturn(new DeleteOutcome(0, List.of(ORIGINAL)));

        mockMvc.perform(delete("/admin/images/api/object").param("name", ORIGINAL)
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.failed[0]").value(ORIGINAL));
    }

    @Test
    void bulkDeleteRemovesTheListedObjectsAndReportsPartialFailures() throws Exception {
        when(browser.delete(List.of(ORIGINAL, TRANSLATED))).thenReturn(new DeleteOutcome(1, List.of(TRANSLATED)));

        mockMvc.perform(post("/admin/images/api/delete")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[\"" + ORIGINAL + "\",\"" + TRANSLATED + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1))
                .andExpect(jsonPath("$.failed[0]").value(TRANSLATED));
    }

    @Test
    void bulkDeleteAcceptsTheCapAndRejectsOneMore() throws Exception {
        when(browser.delete(any())).thenReturn(new DeleteOutcome(MinioObjectBrowser.MAX_DELETE_BATCH, List.of()));

        mockMvc.perform(post("/admin/images/api/delete")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(namesJson(MinioObjectBrowser.MAX_DELETE_BATCH)))
                .andExpect(status().isOk());
        verify(browser).delete(any());

        mockMvc.perform(post("/admin/images/api/delete")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(namesJson(MinioObjectBrowser.MAX_DELETE_BATCH + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("too_many_names"));
        // Still exactly one call: the over-cap request never reached storage.
        verify(browser, org.mockito.Mockito.times(1)).delete(any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"names\":null}", "{\"names\":[]}", "{\"names\":\"images/a.jpg\"}", "[]", "not json", "" })
    void bulkDeleteRejectsMalformedBodies(String body) throws Exception {
        mockMvc.perform(post("/admin/images/api/delete")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        verify(browser, never()).delete(any());
    }

    @Test
    void bulkDeleteWithAnUnmanagedNameIsRejectedByTheBrowserAndRemovesNothing() throws Exception {
        when(browser.delete(List.of(ORIGINAL, "linebot.json"))).thenThrow(new InvalidRequestException());

        mockMvc.perform(post("/admin/images/api/delete")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[\"" + ORIGINAL + "\",\"linebot.json\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    private static String namesJson(int count) {
        StringBuilder json = new StringBuilder("{\"names\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("\"images/00000000-0000-4000-8000-").append(String.format("%012d", i)).append(".jpg\"");
        }
        return json.append("]}").toString();
    }
}
