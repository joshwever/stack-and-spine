package com.stackandspine.service;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the active user's canonical catalog key (`userKey`, normalized lowercase email)
 * across Stage 1 (Cloud Run IAP header) and Stage 2+ (Identity Platform session attribute),
 * with a configurable fallback for local development and tests.
 */
@Component
public class UserIdentityResolver {

    public static final String REQUEST_USER_KEY_ATTR = "stackandspine.userKey";
    public static final String REQUEST_AUTH_SOURCE_ATTR = "stackandspine.authSource";
    public static final String IAP_EMAIL_HEADER = "X-Goog-Authenticated-User-Email";

    private final String defaultLocalUserKey;

    public UserIdentityResolver(
            @Value("${stackandspine.auth.default-local-user:joshwever@gmail.com}") String defaultLocalUser) {
        this.defaultLocalUserKey = normalizeUserKey(defaultLocalUser);
    }

    public String getDefaultUserKey() {
        return defaultLocalUserKey;
    }

    /**
     * Resolves the canonical `userKey` for the current HTTP request:
     * 1. Request attribute set by an application auth filter (Stage 2 Identity Platform)
     * 2. Cloud Run IAP `X-Goog-Authenticated-User-Email` header (`accounts.google.com:user@domain`)
     * 3. Configured local fallback user (`stackandspine.auth.default-local-user`)
     */
    public String resolveUserKey(HttpServletRequest request) {
        if (request != null) {
            Object attr = request.getAttribute(REQUEST_USER_KEY_ATTR);
            if (attr instanceof String s && !s.isBlank()) {
                return normalizeUserKey(s);
            }
            String iapHeader = request.getHeader(IAP_EMAIL_HEADER);
            if (iapHeader != null && !iapHeader.isBlank()) {
                return normalizeUserKey(iapHeader);
            }
        }
        return defaultLocalUserKey;
    }

    /**
     * Returns the authenticated user email when explicitly supplied by IAP or an auth filter,
     * or null when running anonymously on localhost fallback.
     */
    public String resolveAuthenticatedDisplayUser(HttpServletRequest request) {
        if (request != null) {
            Object attr = request.getAttribute(REQUEST_USER_KEY_ATTR);
            if (attr instanceof String s && !s.isBlank()) {
                return normalizeUserKey(s);
            }
            String iapHeader = request.getHeader(IAP_EMAIL_HEADER);
            if (iapHeader != null && !iapHeader.isBlank()) {
                return normalizeUserKey(iapHeader);
            }
        }
        return null;
    }

    /**
     * Returns the label of the active authentication mechanism ("Identity Platform" or "IAP"),
     * or null when using the unauthenticated localhost fallback.
     */
    public String resolveAuthSource(HttpServletRequest request) {
        if (request != null) {
            Object sourceAttr = request.getAttribute(REQUEST_AUTH_SOURCE_ATTR);
            if (sourceAttr instanceof String s && !s.isBlank()) {
                return s;
            }
            Object userAttr = request.getAttribute(REQUEST_USER_KEY_ATTR);
            if (userAttr instanceof String s && !s.isBlank()) {
                return "Identity Platform";
            }
            String iapHeader = request.getHeader(IAP_EMAIL_HEADER);
            if (iapHeader != null && !iapHeader.isBlank()) {
                return "IAP";
            }
        }
        return null;
    }

    /**
     * Normalizes an identity string (e.g. `accounts.google.com:JoshWever@gmail.com`) into a
     * safe, deterministic Firestore document ID (`joshwever@gmail.com`).
     */
    public static String normalizeUserKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("User key cannot be blank");
        }
        String cleaned = raw.trim();
        int colonIdx = cleaned.indexOf(':');
        if (colonIdx >= 0 && colonIdx + 1 < cleaned.length()) {
            cleaned = cleaned.substring(colonIdx + 1).trim();
        }
        cleaned = cleaned.toLowerCase(Locale.ROOT).replace('/', '_');
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            throw new IllegalArgumentException("Invalid normalized user key");
        }
        return cleaned;
    }

    /**
     * Encodes a normalized `userKey` as a single URL-safe, padding-free Base64 GCS path segment
     * (`[A-Za-z0-9_-]+`) so `inbox/<segment>/<uuid>.jpg` round-trips cleanly through Eventarc.
     */
    public static String encodeGcsPathSegment(String userKey) {
        String normalized = normalizeUserKey(userKey);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a GCS path segment produced by {@link #encodeGcsPathSegment(String)} back into
     * the canonical `userKey`.
     */
    public static String decodeGcsPathSegment(String encodedSegment) {
        byte[] decoded = Base64.getUrlDecoder().decode(encodedSegment);
        return normalizeUserKey(new String(decoded, StandardCharsets.UTF_8));
    }
}
