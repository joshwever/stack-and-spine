package com.stackandspine.repository;

import com.stackandspine.model.MediaItem;
import com.stackandspine.model.MediaType;
import com.stackandspine.service.UserIdentityResolver;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Multi-user local in-memory repository. Seeds LibraryThing sample data only into the
 * configured default local user's catalog; additional users start with an empty library.
 */
@Repository
@ConditionalOnProperty(name = "stackandspine.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryMediaRepository implements MediaRepository {

    private final ConcurrentMap<String, Map<String, MediaItem>> storesByUser = new ConcurrentHashMap<>();
    private final LibraryThingSeedLoader seedLoader;
    private final UserIdentityResolver userIdentityResolver;

    public InMemoryMediaRepository(
            LibraryThingSeedLoader seedLoader,
            UserIdentityResolver userIdentityResolver) {
        this.seedLoader = seedLoader;
        this.userIdentityResolver = userIdentityResolver;
    }

    @PostConstruct
    void seedSampleData() {
        String defaultUser = userIdentityResolver.getDefaultUserKey();
        seedLoader.loadBooks().forEach(book -> save(defaultUser, book));
    }

    private Map<String, MediaItem> storeFor(String userKey) {
        String key = UserIdentityResolver.normalizeUserKey(userKey);
        return storesByUser.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
    }

    @Override
    public List<MediaItem> findAll(String userKey) {
        return storeFor(userKey).values().stream()
                .sorted(Comparator.comparing(MediaItem::getTitle, String.CASE_INSENSITIVE_ORDER))
                .toList();
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
        return Optional.ofNullable(storeFor(userKey).get(id));
    }

    @Override
    public MediaItem save(String userKey, MediaItem item) {
        if (item.getId() == null || item.getId().isBlank()) {
            item.setId(UUID.randomUUID().toString());
        }
        storeFor(userKey).put(item.getId(), item);
        return item;
    }

    @Override
    public void deleteById(String userKey, String id) {
        storeFor(userKey).remove(id);
    }

    @Override
    public String storageBackendName() {
        return "In-Memory (Multi-User Isolated)";
    }
}
