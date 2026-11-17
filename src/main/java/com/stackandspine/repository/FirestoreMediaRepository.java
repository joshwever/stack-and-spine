package com.stackandspine.repository;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import com.stackandspine.model.MediaItem;
import com.stackandspine.model.MediaType;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Google Cloud Firestore implementation of {@link MediaRepository}.
 * Active when `stackandspine.storage=firestore` (or `STACKANDSPINE_STORAGE=firestore`).
 *
 * On startup, if the Firestore collection is empty, it automatically seeds the initial
 * 139 books from {@link LibraryThingSeedLoader} using a Firestore WriteBatch.
 */
@Repository
@ConditionalOnProperty(name = "stackandspine.storage", havingValue = "firestore")
public class FirestoreMediaRepository implements MediaRepository {

    private static final Logger log = LoggerFactory.getLogger(FirestoreMediaRepository.class);

    private final Firestore firestore;
    private final LibraryThingSeedLoader seedLoader;
    private final String projectId;
    private final String collectionName;

    public FirestoreMediaRepository(
            Firestore firestore,
            LibraryThingSeedLoader seedLoader,
            @Value("${stackandspine.gcp.project-id}") String projectId,
            @Value("${stackandspine.gcp.collection:media_items}") String collectionName) {
        this.firestore = firestore;
        this.seedLoader = seedLoader;
        this.projectId = projectId;
        this.collectionName = collectionName;
    }

    @PostConstruct
    void seedIfEmpty() {
        try {
            CollectionReference col = collection();
            boolean isEmpty = col.limit(1).get().get().isEmpty();
            if (isEmpty) {
                List<MediaItem> seedBooks = seedLoader.loadBooks();
                log.info(
                        "Firestore collection '{}' in project '{}' is empty. Seeding {} books via WriteBatch...",
                        collectionName,
                        projectId,
                        seedBooks.size());
                WriteBatch batch = firestore.batch();
                for (MediaItem item : seedBooks) {
                    if (item.getId() == null || item.getId().isBlank()) {
                        item.setId(UUID.randomUUID().toString());
                    }
                    DocumentReference docRef = col.document(item.getId());
                    batch.set(docRef, toDocumentMap(item));
                }
                batch.commit().get();
                log.info("Successfully seeded {} books into Firestore collection '{}'", seedBooks.size(), collectionName);
            } else {
                log.info(
                        "Firestore collection '{}' in project '{}' already contains data; skipping initial seed.",
                        collectionName,
                        projectId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking/seeding Firestore collection", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "Failed to connect to or seed Firestore in project '" + projectId + "'. "
                            + "Verify that the Firestore API + database exist and Application Default Credentials are set.",
                    e);
        }
    }

    @Override
    public List<MediaItem> findAll() {
        try {
            List<QueryDocumentSnapshot> docs = collection().get().get().getDocuments();
            return docs.stream()
                    .map(this::fromDocument)
                    .sorted(Comparator.comparing(MediaItem::getTitle, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while querying Firestore", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to query Firestore collection " + collectionName, e);
        }
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
        try {
            DocumentSnapshot doc = collection().document(id).get().get();
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
    public MediaItem save(MediaItem item) {
        if (item.getId() == null || item.getId().isBlank()) {
            item.setId(UUID.randomUUID().toString());
        }
        if (item.getAddedAt() == null) {
            item.setAddedAt(Instant.now());
        }
        try {
            collection().document(item.getId()).set(toDocumentMap(item)).get();
            return item;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while saving Firestore document " + item.getId(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to save Firestore document " + item.getId(), e);
        }
    }

    @Override
    public void deleteById(String id) {
        try {
            collection().document(id).delete().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while deleting Firestore document " + id, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to delete Firestore document " + id, e);
        }
    }

    @Override
    public String storageBackendName() {
        return "Google Cloud Firestore (" + projectId + " / " + collectionName + ")";
    }

    private CollectionReference collection() {
        return firestore.collection(collectionName);
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
        map.put("attributes", item.getAttributes() != null ? item.getAttributes() : Map.of());
        Instant added = item.getAddedAt() != null ? item.getAddedAt() : Instant.now();
        map.put("addedAt", Timestamp.ofTimeSecondsAndNanos(added.getEpochSecond(), added.getNano()));
        return map;
    }

    @SuppressWarnings("unchecked")
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
