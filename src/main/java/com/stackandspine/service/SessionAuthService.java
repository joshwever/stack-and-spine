package com.stackandspine.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.SessionCookieOptions;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Verifies Google Cloud Identity Platform (Firebase Auth) ID tokens and mints/verifies
 * HttpOnly session cookies using keyless Application Default Credentials (ADC).
 */
@Service
public class SessionAuthService {

    private static final Logger log = LoggerFactory.getLogger(SessionAuthService.class);

    public record AuthenticatedSession(String uid, String email) {}

    private final String projectId;
    private final boolean enabled;
    private final String cookieName;
    private final int sessionDurationDays;
    private final String publicApiKey;
    private final String authDomain;

    private volatile FirebaseAuth firebaseAuth;

    public SessionAuthService(
            @Value("${stackandspine.gcp.project-id}") String projectId,
            @Value("${stackandspine.auth.identity-platform.enabled:true}") boolean enabled,
            @Value("${stackandspine.auth.session-cookie-name:ss_session}") String cookieName,
            @Value("${stackandspine.auth.session-duration-days:5}") int sessionDurationDays,
            @Value("${stackandspine.auth.identity-platform.api-key:}") String publicApiKey,
            @Value("${stackandspine.auth.identity-platform.auth-domain:}") String authDomain) {
        this.projectId = projectId;
        this.enabled = enabled;
        this.cookieName = cookieName;
        this.sessionDurationDays = Math.min(Math.max(sessionDurationDays, 1), 14);
        this.publicApiKey = publicApiKey;
        this.authDomain = authDomain;
    }

    public boolean isEnabled() {
        return enabled && publicApiKey != null && !publicApiKey.isBlank();
    }

    public String getCookieName() {
        return cookieName;
    }

    public int getSessionMaxAgeSeconds() {
        return (int) Duration.ofDays(sessionDurationDays).toSeconds();
    }

    public String getPublicApiKey() {
        return publicApiKey;
    }

    public String getAuthDomain() {
        return authDomain;
    }

    public String getProjectId() {
        return projectId;
    }

    /**
     * Verifies a short-lived client Identity Platform ID token and exchanges it for a
     * long-lived HttpOnly session cookie value.
     */
    public String createSessionCookie(String idToken) {
        if (!enabled) {
            throw new IllegalStateException("Identity Platform authentication is disabled.");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("ID token is required.");
        }
        try {
            FirebaseAuth auth = getFirebaseAuth();
            FirebaseToken decoded = auth.verifyIdToken(idToken, true);
            String email = decoded.getEmail();
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("Authenticated account does not have an email address.");
            }
            long expiresInMs = Duration.ofDays(sessionDurationDays).toMillis();
            SessionCookieOptions options = SessionCookieOptions.builder()
                    .setExpiresIn(expiresInMs)
                    .build();
            String cookie = auth.createSessionCookie(idToken, options);
            log.info("Created {}-day Identity Platform session cookie for user '{}'",
                    sessionDurationDays, UserIdentityResolver.normalizeUserKey(email));
            return cookie;
        } catch (FirebaseAuthException e) {
            throw new IllegalArgumentException("Invalid or expired Identity Platform ID token: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize Firebase Admin credentials", e);
        }
    }

    /**
     * Verifies an HttpOnly session cookie (including revocation check) and returns the
     * normalized user identity, or empty if invalid/expired/revoked.
     */
    public Optional<AuthenticatedSession> verifySessionCookie(String sessionCookie) {
        if (!enabled || sessionCookie == null || sessionCookie.isBlank()) {
            return Optional.empty();
        }
        try {
            FirebaseToken decoded = getFirebaseAuth().verifySessionCookie(sessionCookie, true);
            String email = decoded.getEmail();
            if (email == null || email.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedSession(
                    decoded.getUid(),
                    UserIdentityResolver.normalizeUserKey(email)));
        } catch (FirebaseAuthException e) {
            log.debug("Rejected invalid/expired session cookie: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Error verifying Identity Platform session cookie: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Best-effort revocation of the user's refresh tokens on explicit sign-out.
     */
    public void revokeSessionCookie(String sessionCookie) {
        if (!enabled || sessionCookie == null || sessionCookie.isBlank()) {
            return;
        }
        try {
            FirebaseToken decoded = getFirebaseAuth().verifySessionCookie(sessionCookie, false);
            getFirebaseAuth().revokeRefreshTokens(decoded.getUid());
            log.info("Revoked Identity Platform refresh tokens for uid '{}'", decoded.getUid());
        } catch (Exception e) {
            log.debug("Skipping refresh token revocation during logout: {}", e.getMessage());
        }
    }

    private FirebaseAuth getFirebaseAuth() throws IOException {
        if (firebaseAuth == null) {
            synchronized (this) {
                if (firebaseAuth == null) {
                    FirebaseApp app;
                    if (FirebaseApp.getApps().isEmpty()) {
                        FirebaseOptions options = FirebaseOptions.builder()
                                .setCredentials(GoogleCredentials.getApplicationDefault())
                                .setProjectId(projectId)
                                .build();
                        app = FirebaseApp.initializeApp(options);
                    } else {
                        app = FirebaseApp.getInstance();
                    }
                    firebaseAuth = FirebaseAuth.getInstance(app);
                }
            }
        }
        return firebaseAuth;
    }
}
