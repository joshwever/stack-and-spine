package com.stackandspine.repository;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.stackandspine.model.MediaItem;
import com.stackandspine.model.MediaType;
import com.stackandspine.service.UserIdentityResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Multi-tenant Google Cloud Firestore implementation of {@link MediaRepository}.
 * Active when `stackandspine.storage=firestore` (or `STACKANDSPINE_STORAGE=firestore`).
 *
 * Isolates each user's catalog in a dedicated subcollection:
 * `users/{userKey}/media_items`, with an independent per-user 60s read-through cache.
 */
@Repository
@ConditionalOnProperty(name = "stackandspine.storage", havingValue = "firestore")
public class FirestoreMediaRepository implements MediaRepository {

    private static final Logger log = LoggerFactory.getLogger(FirestoreMediaRepository.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private record CachedCatalog(List<MediaItem> items, Instant loadedAt) {}

    private final Firestore firestore;
    private final String projectId;
    private final String usersCollectionName;
    private final String collectionName;

    private final ConcurrentMap<String, CachedCatalog> cacheByUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locksByUser = new ConcurrentHashMap<>();

    public FirestoreMediaRepository(
            Firestore firestore,
            @Value("${stackandspine.gcp.project-id}") String projectId,
            @Value("${stackandspine.gcp.users-collection:users}") String usersCollectionName,
            @Value("${stackandspine.gcp.collection:media_items}") String collectionName) {
        this.firestore = firestore;
        this.projectId = projectId;
        this.usersCollectionName = usersCollectionName;
        this.collectionName = collectionName;
    }

    @Override
    public List<MediaItem> findAll(String userKey) {
        String key = UserIdentityResolver.normalizeUserKey(userKey);
        CachedCatalog cached = cacheByUser.get(key);
        if (cached != null && Duration.between(cached.loadedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached.items();
        }
        Object userLock = locksByUser.computeIfAbsent(key, k -> new Object());
        synchronized (userLock) {
            cached = cacheByUser.get(key);
            if (cached != null && Duration.between(cached.loadedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
                return cached.items();
            }
            try {
                List<QueryDocumentSnapshot> docs = userCollection(key).get().get().getDocuments();
                List<MediaItem> loaded = docs.stream()
                        .map(this::fromDocument)
                        .sorted(Comparator.comparing(MediaItem::getTitle, String.CASE_INSENSITIVE_ORDER))
                        .toList();
                cacheByUser.put(key, new CachedCatalog(loaded, Instant.now()));
                log.info("Refreshed Firestore read-through cache for user '{}' ({} items)", key, loaded.size());
                return loaded;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while querying Firestore for user " + key, e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Failed to query Firestore catalog for user " + key, e);
            }
        }
    }

    private void invalidateUserCache(String normalizedUserKey) {
        cacheByUser.remove(normalizedUserKey);
    }

    @Override
    public List<MediaItem> findByTypeAndQuery(String userKey, MediaType mediaType, String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return findAll(userKey).stream()
                .filter(item -> mediaType == null || item.getMediaType() == mediaType)
                .filter(item -> normalizedQuery.isEmpty()
                        || item.getTitle().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        || item.getCreator().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        || (item.getIdentifierCode() != null
                                && item.getIdentifierCode().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                        || (item.getGenre() != null
                                && item.getGenre().toLowerCase(Locale.ROOT).contains(normalizedQuery)))
                .toList();
    }

    @Override
    public Optional<MediaItem> findById(String userKey, String id) {
        String key = UserIdentityResolver.normalizeUserKey(userKey);
        try {
            DocumentSnapshot doc = userCollection(key).document(id).get().get();
            if (!doc.exists()) {
                return Optional.empty();
            }
            return Optional.of(fromDocument(doc));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching Firestore document " + id, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to fetch Firestore document " + id, e);
        }
    }

    @Override
    public MediaItem save(String userKey, MediaItem item) {
        String key = UserIdentityResolver.normalizeUserKey(userKey);
        if (item.getId() == null || item.getId().isBlank()) {
            item.setId(UUID.randomUUID().toString());
        }
        if (item.getAddedAt() == null) {
            item.setAddedAt(Instant.now());
        }
        try {
            userCollection(key).document(item.getId()).set(toDocumentMap(item)).get();
            invalidateUserCache(key);
            return item;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while saving Firestore document " + item.getId(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to save Firestore document " + item.getId(), e);
        }
    }

    @Override
    public void deleteById(String userKey, String id) {
        String key = UserIdentityResolver.normalizeUserKey(userKey);
        try {
            userCollection(key).document(id).delete().get();
            invalidateUserCache(key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while deleting Firestore document " + id, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to delete Firestore document " + id, e);
        }
    }

    @Override
    public String storageBackendName() {
        return "Google Cloud Firestore (" + projectId + " / " + usersCollectionName + "/{user}/" + collectionName + ")";
    }

    private CollectionReference userCollection(String normalizedUserKey) {
        return firestore.collection(usersCollectionName)
                .document(normalizedUserKey)
                .collection(collectionName);
    }

    private Map<String, Object> toDocumentMap(MediaItem item) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", item.getId());
        map.put("title", item.getTitle());
        map.put("mediaType", item.getMediaType() != null ? item.getMediaType().name() : MediaType.BOOK.name());
        map.put("creator", item.getCreator());
        map.put("releaseYear", item.getReleaseYear());
        map.put("identifierCode", item.getIdentifierCode());
        map.put("genre", item.getGenre());
        map.put("shelfLocation", item.getShelfLocation());
        map.put("description", item.getDescription());
        map.put("coverObjectPath", item.getCoverObjectPath());
        map.put("attributes", item.getAttributes() != null ? item.getAttributes() : Map.of());
        Instant added = item.getAddedAt() != null ? item.getAddedAt() : Instant.now();
        map.put("addedAt", Timestamp.ofTimeSecondsAndNanos(added.getEpochSecond(), added.getNano()));
        return map;
    }

    private MediaItem fromDocument(DocumentSnapshot doc) {
        MediaItem item = new MediaItem();
        item.setId(doc.getId());
        item.setTitle(doc.getString("title"));

        String mediaTypeStr = doc.getString("mediaType");
        if (mediaTypeStr != null) {
            try {
                item.setMediaType(MediaType.valueOf(mediaTypeStr));
            } catch (IllegalArgumentException ignored) {
                item.setMediaType(MediaType.BOOK);
            }
        } else {
            item.setMediaType(MediaType.BOOK);
        }

        item.setCreator(doc.getString("creator"));
        Long releaseYear = doc.getLong("releaseYear");
        if (releaseYear != null) {
            item.setReleaseYear(releaseYear.intValue());
        }
        item.setIdentifierCode(doc.getString("identifierCode"));
        item.setGenre(doc.getString("genre"));
        item.setShelfLocation(doc.getString("shelfLocation"));
        item.setDescription(doc.getString("description"));
        item.setCoverObjectPath(doc.getString("coverObjectPath"));

        Object rawAttrs = doc.get("attributes");
        if (rawAttrs instanceof Map<?, ?> rawMap) {
            Map<String, String> attrs = new HashMap<>();
            rawMap.forEach((k, v) -> {
                if (k != null && v != null) {
                    attrs.put(String.valueOf(k), String.valueOf(v));
                }
            });
            item.setAttributes(attrs);
        }

        Timestamp ts = doc.getTimestamp("addedAt");
        if (ts != null) {
            item.setAddedAt(Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()));
        }
        return item;
    }
}
