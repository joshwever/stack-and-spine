package com.stackandspine.repository;

import com.stackandspine.model.MediaItem;
import com.stackandspine.model.MediaType;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Local in-memory repository seeded from LibraryThing export (librarythingBookList.json).
 * In Phase 2, FirestoreMediaRepository (activated via stackandspine.storage=firestore)
 * will use the same LibraryThingSeedLoader to seed the initial GCP Firestore collection.
 */
@Repository
@ConditionalOnProperty(name = "stackandspine.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryMediaRepository implements MediaRepository {

    private final Map<String, MediaItem> store = new ConcurrentHashMap<>();
    private final LibraryThingSeedLoader seedLoader;

    public InMemoryMediaRepository(LibraryThingSeedLoader seedLoader) {
        this.seedLoader = seedLoader;
    }

    @PostConstruct
    void seedSampleData() {
        seedLoader.loadBooks().forEach(this::save);
    }

    @Override
    public List<MediaItem> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(MediaItem::getTitle, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public List<MediaItem> findByTypeAndQuery(MediaType mediaType, String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return findAll().stream()
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
    public Optional<MediaItem> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public MediaItem save(MediaItem item) {
        if (item.getId() == null || item.getId().isBlank()) {
            item.setId(UUID.randomUUID().toString());
        }
        store.put(item.getId(), item);
        return item;
    }

    @Override
    public void deleteById(String id) {
        store.remove(id);
    }

    @Override
    public String storageBackendName() {
        return "In-Memory (LibraryThing Seed — Firestore Ready)";
    }
}
