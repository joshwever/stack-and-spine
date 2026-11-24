package com.stackandspine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stackandspine.service.AiScanQuotaService;
import com.stackandspine.service.UserIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "stackandspine.storage=memory")
@AutoConfigureMockMvc
class StackAndSpineApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiScanQuotaService aiScanQuotaService;

    @Test
    void contextLoadsAndReturnsSeededLibraryThingCatalog() throws Exception {
        mockMvc.perform(get("/api/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(139));

        mockMvc.perform(get("/api/items").param("q", "Neal Stephenson"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].creator").value("Neal Stephenson"));
    }

    @Test
    void indexRendersFullPageAndHtmxFragment() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"main-layout\"")))
                .andExpect(content().string(containsString("id=\"add-item-form\"")))
                .andExpect(content().string(containsString("id=\"catalog-grid\"")));

        mockMvc.perform(get("/").header("HX-Request", "true").param("q", "Neal Stephenson"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"catalog-grid\"")))
                .andExpect(content().string(not(containsString("id=\"main-layout\""))));
    }

    @Test
    void editAndUpdateItemRendersInlineCard() throws Exception {
        String id = "lt-230813791";

        mockMvc.perform(get("/items/" + id + "/edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Edit Media Item")))
                .andExpect(content().string(containsString("The Last Duel")));

        mockMvc.perform(get("/items/" + id + "/card"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("item-card-" + id)));

        mockMvc.perform(post("/items/" + id)
                        .param("mediaType", "EBOOK")
                        .param("title", "The Last Duel (Edited)")
                        .param("creator", "Eric Jager")
                        .param("releaseYear", "2005")
                        .param("genre", "History")
                        .param("shelfLocation", "Study - Top")
                        .param("attributesText", "Format: EPUB\nPages: 256"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The Last Duel (Edited)")))
                .andExpect(content().string(containsString("E-Book")))
                .andExpect(content().string(containsString("hx-swap-oob=\"true\"")));
    }

    @Test
    void multiUserCatalogsAreStrictlyIsolatedByIapHeader() throws Exception {
        String aliceHeader = "accounts.google.com:Alice.Reader@example.com";

        // Alice starts with a completely empty personal library
        mockMvc.perform(get("/api/items")
                        .header(UserIdentityResolver.IAP_EMAIL_HEADER, aliceHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/")
                        .header(UserIdentityResolver.IAP_EMAIL_HEADER, aliceHeader))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alice.reader@example.com")));

        // Alice adds an item to her personal library
        mockMvc.perform(post("/items")
                        .header(UserIdentityResolver.IAP_EMAIL_HEADER, aliceHeader)
                        .param("mediaType", "BLU_RAY")
                        .param("title", "Blade Runner 2049")
                        .param("creator", "Denis Villeneuve")
                        .param("releaseYear", "2017"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        // Alice sees her 1 item
        mockMvc.perform(get("/api/items")
                        .header(UserIdentityResolver.IAP_EMAIL_HEADER, aliceHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Blade Runner 2049"));

        // Default user (joshwever@gmail.com) does NOT see Alice's item, and Alice cannot delete Josh's items
        mockMvc.perform(get("/api/items").param("q", "Blade Runner 2049"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(delete("/items/lt-230813791")
                        .header(UserIdentityResolver.IAP_EMAIL_HEADER, aliceHeader))
                .andExpect(status().isOk());

        mockMvc.perform(get("/items/lt-230813791/card"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("item-card-lt-230813791")));
    }

    @Test
    void userIdentityResolverEncodesAndDecodesGcsSegmentsLosslessly() {
        String normalized = UserIdentityResolver.normalizeUserKey("accounts.google.com:JoshWever@Gmail.com");
        assertThat(normalized).isEqualTo("joshwever@gmail.com");

        String segment = UserIdentityResolver.encodeGcsPathSegment(normalized);
        assertThat(segment).doesNotContain("/").doesNotContain("=");
        assertThat(UserIdentityResolver.decodeGcsPathSegment(segment)).isEqualTo("joshwever@gmail.com");
    }

    @Test
    void aiScanQuotaCapsRegularUsersAt15AndExemptsAdmin() throws Exception {
        String regularUser = "quota-tester@example.com";
        String adminUser = "joshwever@gmail.com";

        assertThat(aiScanQuotaService.isAdmin(adminUser)).isTrue();
        assertThat(aiScanQuotaService.isAdmin(regularUser)).isFalse();
        assertThat(aiScanQuotaService.getStatus(adminUser).unlimited()).isTrue();

        // Admin can exceed 15 scans with no cap
        for (int i = 0; i < 25; i++) {
            assertThat(aiScanQuotaService.tryConsume(adminUser)).isTrue();
        }

        // Regular user is capped at exactly 15 scans
        for (int i = 0; i < 15; i++) {
            assertThat(aiScanQuotaService.tryConsume(regularUser)).isTrue();
        }
        assertThat(aiScanQuotaService.tryConsume(regularUser)).isFalse();
        assertThat(aiScanQuotaService.getStatus(regularUser).remaining()).isZero();

        // 16th upload via /inbox/scan redirects to /?quotaExceeded=true before touching GCS
        MockMultipartFile dummyPhoto = new MockMultipartFile(
                "file", "cover.jpg", "image/jpeg", new byte[] {1, 2, 3});
        mockMvc.perform(multipart("/inbox/scan")
                        .file(dummyPhoto)
                        .header(UserIdentityResolver.IAP_EMAIL_HEADER, "accounts.google.com:" + regularUser))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?quotaExceeded=true"));
    }
}
