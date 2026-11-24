package com.stackandspine;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stackandspine.service.SessionAuthService;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "stackandspine.storage=memory",
        "stackandspine.auth.mode=identity-platform"
})
@AutoConfigureMockMvc
class IdentityPlatformAuthTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionAuthService sessionAuthService;

    @BeforeEach
    void setUpDefaults() {
        when(sessionAuthService.getCookieName()).thenReturn("ss_session");
        when(sessionAuthService.getSessionMaxAgeSeconds()).thenReturn(432000);
        when(sessionAuthService.isEnabled()).thenReturn(true);
        when(sessionAuthService.getPublicApiKey()).thenReturn("test-api-key");
        when(sessionAuthService.getAuthDomain()).thenReturn("test-project.firebaseapp.com");
        when(sessionAuthService.getProjectId()).thenReturn("test-project");
    }

    @Test
    void unauthenticatedRequestsAreRedirectedOrRejectedInStrictMode() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/api/items"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required"));

        mockMvc.perform(get("/").header("HX-Request", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/login"));

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Continue with Google")))
                .andExpect(content().string(containsString("Create Account")));
    }

    @Test
    void sessionExchangeSetsHttpOnlyCookieAndAuthenticatesUserCatalog() throws Exception {
        when(sessionAuthService.createSessionCookie("valid-id-token")).thenReturn("minted-session-cookie");
        when(sessionAuthService.verifySessionCookie("minted-session-cookie"))
                .thenReturn(Optional.of(new SessionAuthService.AuthenticatedSession("uid-bob", "bob@example.com")));

        mockMvc.perform(post("/auth/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-Proto", "https")
                        .content("{\"idToken\":\"valid-id-token\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("ss_session=minted-session-cookie")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")));

        // Valid session cookie loads Bob's isolated empty library and renders Identity Platform + Sign Out
        mockMvc.perform(get("/api/items")
                        .cookie(new Cookie("ss_session", "minted-session-cookie")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/")
                        .cookie(new Cookie("ss_session", "minted-session-cookie")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Identity Platform")))
                .andExpect(content().string(containsString("bob@example.com")))
                .andExpect(content().string(containsString("Sign Out")));

        // Logout revokes token and clears cookie
        mockMvc.perform(post("/auth/logout")
                        .cookie(new Cookie("ss_session", "minted-session-cookie")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?loggedOut=true"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        verify(sessionAuthService).revokeSessionCookie("minted-session-cookie");
    }
}
