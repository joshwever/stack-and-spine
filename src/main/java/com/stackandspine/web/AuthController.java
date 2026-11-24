package com.stackandspine.web;

import com.stackandspine.service.SessionAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Handles the Identity Platform `/login` page, session cookie exchange (`POST /auth/session`),
 * and explicit sign-out (`POST /auth/logout`).
 */
@Controller
public class AuthController {

    private final SessionAuthService sessionAuthService;

    public AuthController(SessionAuthService sessionAuthService) {
        this.sessionAuthService = sessionAuthService;
    }

    @GetMapping("/login")
    public String loginPage(
            @RequestParam(name = "loggedOut", required = false) Boolean loggedOut,
            HttpServletRequest request,
            Model model) {
        String existingCookie = SessionAuthFilter.extractCookie(request, sessionAuthService.getCookieName());
        String signedInEmail = sessionAuthService.verifySessionCookie(existingCookie)
                .map(SessionAuthService.AuthenticatedSession::email)
                .orElse(null);

        model.addAttribute("identityEnabled", sessionAuthService.isEnabled());
        model.addAttribute("identityApiKey", sessionAuthService.getPublicApiKey());
        model.addAttribute("identityAuthDomain", sessionAuthService.getAuthDomain());
        model.addAttribute("identityProjectId", sessionAuthService.getProjectId());
        model.addAttribute("signedInEmail", signedInEmail);
        model.addAttribute("loggedOut", Boolean.TRUE.equals(loggedOut));
        return "login";
    }

    @PostMapping("/auth/session")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createSession(
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        String idToken = body != null ? body.get("idToken") : null;
        if (idToken == null || idToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing idToken"));
        }
        try {
            String sessionCookieValue = sessionAuthService.createSessionCookie(idToken);
            ResponseCookie cookie = ResponseCookie.from(sessionAuthService.getCookieName(), sessionCookieValue)
                    .httpOnly(true)
                    .secure(SessionAuthFilter.isSecureRequest(request))
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(sessionAuthService.getSessionMaxAgeSeconds())
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
            return ResponseEntity.ok(Map.of("status", "ok", "redirect", "/"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not create session: " + e.getMessage()));
        }
    }

    @PostMapping("/auth/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        String existingCookie = SessionAuthFilter.extractCookie(request, sessionAuthService.getCookieName());
        if (existingCookie != null && !existingCookie.isBlank()) {
            sessionAuthService.revokeSessionCookie(existingCookie);
        }
        ResponseCookie expired = ResponseCookie.from(sessionAuthService.getCookieName(), "")
                .httpOnly(true)
                .secure(SessionAuthFilter.isSecureRequest(request))
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
        return "redirect:/login?loggedOut=true";
    }
}
