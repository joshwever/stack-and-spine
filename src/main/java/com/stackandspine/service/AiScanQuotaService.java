package com.stackandspine.service;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Enforces a per-user daily quota on AI Photo Scans (GCS inbox + Vertex AI Gemini),
 * exempting configured admin users (e.g. `joshwever@gmail.com`).
 *
 * Uses an atomic Firestore transaction on `users/{userKey}/usage/ai_scans_{YYYY-MM-DD}`
 * when Firestore is active so limits hold across Cloud Run instances and cold starts,
 * and falls back to an atomic in-memory map in local/test memory mode.
 */
@Service
public class AiScanQuotaService {

    private static final Logger log = LoggerFactory.getLogger(AiScanQuotaService.class);
    private static final String USAGE_SUBCOLLECTION = "usage";

    public record QuotaStatus(boolean unlimited, int usedToday, int dailyLimit, int remaining) {}

    private final Optional<Firestore> firestore;
    private final String usersCollectionName;
    private final int dailyLimit;
    private final Set<String> adminUsers;
    private final ConcurrentMap<String, Integer> memoryCounters = new ConcurrentHashMap<>();

    public AiScanQuotaService(
            Optional<Firestore> firestore,
            @Value("${stackandspine.gcp.users-collection:users}") String usersCollectionName,
            @Value("${stackandspine.quota.ai-scan-daily-limit:15}") int dailyLimit,
            @Value("${stackandspine.quota.admin-users:joshwever@gmail.com}") String adminUsersCsv) {
        this.firestore = firestore;
        this.usersCollectionName = usersCollectionName;
        this.dailyLimit = Math.max(0, dailyLimit);
        this.adminUsers = Arrays.stream((adminUsersCsv == null ? "" : adminUsersCsv).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UserIdentityResolver::normalizeUserKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(String userKey) {
        String normalized = UserIdentityResolver.normalizeUserKey(userKey);
        return adminUsers.contains(normalized);
    }

    public int getDailyLimit() {
        return dailyLimit;
    }

    public QuotaStatus getStatus(String userKey) {
        String key = UserIdentityResolver.normalizeUserKey(userKey);
        if (adminUsers.contains(key)) {
            return new QuotaStatus(true, 0, dailyLimit, Integer.MAX_VALUE);
        }
        int used = readTodayCount(key);
        int remaining = Math.max(0, dailyLimit - used);
        return new QuotaStatus(false, used, dailyLimit, remaining);
    }

    /**
     * Atomically checks and increments the user's daily AI scan counter.
     * Returns true if the scan is permitted, or false if the daily limit has been reached.
     */
    public boolean tryConsume(String userKey) {
        String key = UserIdentityResolver.normalizeUserKey(userKey);
        if (adminUsers.contains(key)) {
            return true;
        }
        if (dailyLimit <= 0) {
            return false;
        }

        String dateKey = todayUtc();
        if (firestore.isPresent()) {
            return consumeInFirestore(firestore.get(), key, dateKey);
        }

        String mapKey = key + ":" + dateKey;
        AtomicBoolean allowed = new AtomicBoolean(false);
        memoryCounters.compute(mapKey, (k, existing) -> {
            int current = existing == null ? 0 : existing;
            if (current >= dailyLimit) {
                allowed.set(false);
                return current;
            }
            allowed.set(true);
            return current + 1;
        });
        return allowed.get();
    }

    private int readTodayCount(String normalizedUserKey) {
        String dateKey = todayUtc();
        if (firestore.isPresent()) {
            try {
                DocumentSnapshot snap = usageDoc(firestore.get(), normalizedUserKey, dateKey).get().get();
                if (!snap.exists()) {
                    return 0;
                }
                Long count = snap.getLong("count");
                return count != null ? count.intValue() : 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted reading AI scan quota for '{}'", normalizedUserKey);
                return 0;
            } catch (ExecutionException e) {
                log.warn("Failed reading AI scan quota for '{}': {}", normalizedUserKey, e.getMessage());
                return 0;
            }
        }
        return memoryCounters.getOrDefault(normalizedUserKey + ":" + dateKey, 0);
    }

    private boolean consumeInFirestore(Firestore db, String normalizedUserKey, String dateKey) {
        DocumentReference docRef = usageDoc(db, normalizedUserKey, dateKey);
        try {
            Boolean allowed = db.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(docRef).get();
                long current = (snap.exists() && snap.getLong("count") != null) ? snap.getLong("count") : 0L;
                if (current >= dailyLimit) {
                    return false;
                }
                Instant now = Instant.now();
                Map<String, Object> payload = new HashMap<>();
                payload.put("count", current + 1);
                payload.put("date", dateKey);
                payload.put("limit", dailyLimit);
                payload.put("updatedAt", Timestamp.ofTimeSecondsAndNanos(now.getEpochSecond(), now.getNano()));
                tx.set(docRef, payload);
                return true;
            }).get();
            boolean result = Boolean.TRUE.equals(allowed);
            if (!result) {
                log.info("AI scan daily quota ({}) exceeded for user '{}' on {}", dailyLimit, normalizedUserKey, dateKey);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking AI scan quota", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to update AI scan quota in Firestore", e);
        }
    }

    private DocumentReference usageDoc(Firestore db, String normalizedUserKey, String dateKey) {
        return db.collection(usersCollectionName)
                .document(normalizedUserKey)
                .collection(USAGE_SUBCOLLECTION)
                .document("ai_scans_" + dateKey);
    }

    private static String todayUtc() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }
}
