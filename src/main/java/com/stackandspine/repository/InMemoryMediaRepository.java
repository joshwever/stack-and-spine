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
 * Phase 1 starter repository backed by an in-memory store with realistic sample media.
 * In Phase 2, we will add FirestoreMediaRepository (activated via stackandspine.storage=firestore)
 * so you can compare local vs. Google Cloud Firestore side-by-side!
 */
@Repository
@ConditionalOnProperty(name = "stackandspine.storage", havingValue = "memory", matchIfMissing = true)
public class InMemoryMediaRepository implements MediaRepository {

    private final Map<String, MediaItem> store = new ConcurrentHashMap<>();

    @PostConstruct
    void seedSampleData() {
        save(new MediaItem(
                "item-1",
                "Dune (Deluxe Hardcover)",
                MediaType.BOOK,
                "Frank Herbert",
                1965,
                "978-0441013593",
                "Sci-Fi",
                "Study - Shelf A1",
                Map.of("Pages", "688", "Format", "Hardcover", "Publisher", "Ace Books")));

        save(new MediaItem(
                "item-2",
                "Blade Runner 2049 (4K Ultra HD + Blu-Ray)",
                MediaType.BLU_RAY,
                "Denis Villeneuve",
                2017,
                "883929596270",
                "Sci-Fi / Neo-Noir",
                "Living Room - Media Rack 1",
                Map.of("Resolution", "4K UHD / 1080p", "Audio", "Dolby Atmos", "Region", "A/B/C (Region Free)")));

        save(new MediaItem(
                "item-3",
                "The Fellowship of the Ring (Extended Edition)",
                MediaType.DVD,
                "Peter Jackson",
                2002,
                "794043554926",
                "Fantasy",
                "Living Room - Media Rack 2",
                Map.of("Discs", "4", "Edition", "Special Extended DVD", "Aspect Ratio", "2.35:1")));

        save(new MediaItem(
                "item-4",
                "Designing Data-Intensive Applications",
                MediaType.BOOK,
                "Martin Kleppmann",
                2017,
                "978-1449373320",
                "Software Architecture",
                "Office - Desk Shelf",
                Map.of("Pages", "616", "Format", "Paperback", "Publisher", "O'Reilly Media")));
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
        return "In-Memory (Phase 1 Starter — Firestore Ready)";
    }
}
