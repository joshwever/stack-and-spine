package com.stackandspine.web;

import com.stackandspine.service.SessionAuthService;
import com.stackandspine.service.UserIdentityResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates incoming requests using Identity Platform HttpOnly session cookies.
 *
 * - In `dual` mode (Stage 2): validates any present `ss_session` cookie (setting the
 *   authenticated userKey for downstream controllers), while still permitting IAP-authenticated
 *   or local fallback requests when no session cookie is set.
 * - In `identity-platform` mode (Stage 3): requires a valid `ss_session` cookie for all
 *   non-public routes, redirecting browser requests to `/login` and returning 401 for `/api/**`.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SessionAuthFilter extends OncePerRequestFilter {

    private final SessionAuthService sessionAuthService;
    private final String authMode;

    public SessionAuthFilter(
            SessionAuthService sessionAuthService,
            @Value("${stackandspine.auth.mode:dual}") String authMode) {
        this.sessionAuthService = sessionAuthService;
        this.authMode = authMode;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/login")
                || path.startsWith("/auth/")
                || path.startsWith("/events/")
                || path.startsWith("/actuator/")
                || path.startsWith("/js/")
                || path.startsWith("/css/")
                || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String cookieValue = extractCookie(request, sessionAuthService.getCookieName());
        if (cookieValue != null && !cookieValue.isBlank()) {
            Optional<SessionAuthService.AuthenticatedSession> session =
                    sessionAuthService.verifySessionCookie(cookieValue);
            if (session.isPresent()) {
                request.setAttribute(UserIdentityResolver.REQUEST_USER_KEY_ATTR, session.get().email());
                request.setAttribute(UserIdentityResolver.REQUEST_AUTH_SOURCE_ATTR, "Identity Platform");
                filterChain.doFilter(request, response);
                return;
            }
            // Clear invalid or expired session cookie
            ResponseCookie expired = ResponseCookie.from(sessionAuthService.getCookieName(), "")
                    .httpOnly(true)
                    .secure(isSecureRequest(request))
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(0)
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
        }

        if ("identity-platform".equalsIgnoreCase(authMode)) {
            String path = request.getRequestURI();
            if (path.startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Authentication required\"}");
                return;
            }
            if ("true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
                response.setHeader("HX-Redirect", "/login");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            response.sendRedirect("/login");
            return;
        }

        filterChain.doFilter(request, response);
    }

    static String extractCookie(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    static boolean isSecureRequest(HttpServletRequest request) {
        return request.isSecure()
                || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }
}
