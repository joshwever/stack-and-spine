package com.stackandspine.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackandspine.model.MediaItem;
import com.stackandspine.model.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the initial book dataset exported from LibraryThing (librarythingBookList.json).
 * Shared by both the local InMemoryMediaRepository and the GCP FirestoreMediaRepository
 * so both environments start with the exact same library catalog.
 */
@Component
public class LibraryThingSeedLoader {

    private static final Logger log = LoggerFactory.getLogger(LibraryThingSeedLoader.class);
    private static final String SEED_RESOURCE_PATH = "librarythingBookList.json";
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(\\d{4})\\b");

    private final ObjectMapper objectMapper;

    public LibraryThingSeedLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<MediaItem> loadBooks() {
        ClassPathResource resource = new ClassPathResource(SEED_RESOURCE_PATH);
        if (!resource.exists()) {
            log.warn("Seed resource {} not found on classpath; returning empty list", SEED_RESOURCE_PATH);
            return List.of();
        }

        try (InputStream is = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            List<MediaItem> items = new ArrayList<>();

            for (Map.Entry<String, JsonNode> entry : root.properties()) {
                MediaItem item = mapBookNode(entry.getKey(), entry.getValue());
                if (item != null) {
                    items.add(item);
                }
            }

            log.info("Loaded {} books from {}", items.size(), SEED_RESOURCE_PATH);
            return items;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load LibraryThing seed data from " + SEED_RESOURCE_PATH, e);
        }
    }

    private MediaItem mapBookNode(String key, JsonNode node) {
        String booksId = textOrNull(node, "books_id");
        if (booksId == null || booksId.isBlank()) {
            booksId = key;
        }

        String title = textOrNull(node, "title");
        if (title == null || title.isBlank()) {
            return null;
        }

        String creator = extractCreator(node);
        Integer releaseYear = extractYear(textOrNull(node, "date"));
        String identifierCode = extractIsbn(node);
        String genre = extractGenre(node);
        String shelfLocation = extractShelfLocation(node);
        Map<String, String> attributes = extractAttributes(node, booksId, identifierCode);
        MediaType mediaType = "Ebook".equalsIgnoreCase(attributes.get("Format"))
                ? MediaType.EBOOK
                : MediaType.BOOK;

        MediaItem item = new MediaItem(
                "lt-" + booksId,
                title.trim(),
                mediaType,
                creator,
                releaseYear,
                identifierCode,
                genre,
                shelfLocation,
                attributes);

        Instant addedAt = extractEntryDate(textOrNull(node, "entrydate"));
        if (addedAt != null) {
            item.setAddedAt(addedAt);
        }

        return item;
    }

    private String extractCreator(JsonNode node) {
        JsonNode authorsNode = node.get("authors");
        if (authorsNode != null && authorsNode.isArray() && !authorsNode.isEmpty()) {
            JsonNode firstAuthor = authorsNode.get(0);
            String fl = textOrNull(firstAuthor, "fl");
            if (fl != null && !fl.isBlank()) {
                return fl.trim();
            }
            String lf = textOrNull(firstAuthor, "lf");
            if (lf != null && !lf.isBlank()) {
                return lf.trim();
            }
        }
        String primaryAuthor = textOrNull(node, "primaryauthor");
        return (primaryAuthor != null && !primaryAuthor.isBlank()) ? primaryAuthor.trim() : "Unknown Author";
    }

    private Integer extractYear(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        Matcher matcher = YEAR_PATTERN.matcher(dateStr);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String extractIsbn(JsonNode node) {
        JsonNode isbnNode = node.get("isbn");
        if (isbnNode != null) {
            if (isbnNode.isObject()) {
                // LibraryThing stores ISBN-13 under key "2" and ISBN-10 under key "0"
                String isbn13 = textOrNull(isbnNode, "2");
                if (isbn13 != null && !isbn13.isBlank()) {
                    return isbn13.trim();
                }
                String isbn10 = textOrNull(isbnNode, "0");
                if (isbn10 != null && !isbn10.isBlank()) {
                    return isbn10.trim();
                }
                Iterator<JsonNode> elements = isbnNode.elements();
                if (elements.hasNext()) {
                    String first = elements.next().asText(null);
                    if (first != null && !first.isBlank()) {
                        return first.trim();
                    }
                }
            } else if (isbnNode.isArray() && !isbnNode.isEmpty()) {
                String first = isbnNode.get(0).asText(null);
                if (first != null && !first.isBlank()) {
                    return first.trim();
                }
            }
        }
        String originalIsbn = textOrNull(node, "originalisbn");
        if (originalIsbn != null && !originalIsbn.isBlank()) {
            return originalIsbn.trim();
        }
        String asin = textOrNull(node, "asin");
        return (asin != null && !asin.isBlank()) ? asin.trim() : null;
    }

    private String extractGenre(JsonNode node) {
        List<String> genres = stringList(node.get("genre"));
        if (!genres.isEmpty()) {
            return String.join(", ", genres);
        }
        JsonNode ddcNode = node.get("ddc");
        if (ddcNode != null && ddcNode.isObject()) {
            List<String> wording = stringList(ddcNode.get("wording"));
            if (!wording.isEmpty()) {
                return wording.get(0);
            }
        }
        return null;
    }

    private String extractShelfLocation(JsonNode node) {
        List<String> collections = stringList(node.get("collections"));
        if (!collections.isEmpty()) {
            return String.join(" • ", collections);
        }
        return "Your library";
    }

    private Map<String, String> extractAttributes(JsonNode node, String booksId, String primaryIsbn) {
        Map<String, String> attrs = new LinkedHashMap<>();

        JsonNode formatNode = node.get("format");
        if (formatNode != null && formatNode.isArray() && !formatNode.isEmpty()) {
            String formatText = textOrNull(formatNode.get(0), "text");
            if (formatText != null && !formatText.isBlank()) {
                attrs.put("Format", formatText.trim());
            }
        }

        String pages = textOrNull(node, "pages");
        if (pages != null && !pages.isBlank()) {
            attrs.put("Pages", pages.trim());
        }

        List<String> seriesList = stringList(node.get("series"));
        if (!seriesList.isEmpty()) {
            // Take the last English/canonical entry or first non-blank series name
            attrs.put("Series", seriesList.get(seriesList.size() - 1));
        }

        String publication = textOrNull(node, "publication");
        if (publication != null && !publication.isBlank()) {
            attrs.put("Publication", publication.trim());
        }

        String originalIsbn = textOrNull(node, "originalisbn");
        if (originalIsbn != null && !originalIsbn.isBlank() && !originalIsbn.trim().equals(primaryIsbn)) {
            attrs.put("ISBN-10", originalIsbn.trim());
        }

        JsonNode lccNode = node.get("lcc");
        if (lccNode != null && lccNode.isObject()) {
            String lccCode = textOrNull(lccNode, "code");
            if (lccCode != null && !lccCode.isBlank()) {
                attrs.put("LCC", lccCode.trim());
            }
        }

        attrs.put("LibraryThing ID", booksId);
        return attrs;
    }

    private Instant extractEntryDate(String entryDate) {
        if (entryDate == null || entryDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(entryDate.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String textOrNull(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        JsonNode child = node.get(fieldName);
        if (child == null || child.isNull() || !child.isValueNode()) {
            return null;
        }
        return child.asText();
    }

    private List<String> stringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            if (element != null && element.isValueNode()) {
                String val = element.asText("").trim();
                if (!val.isEmpty()) {
                    result.add(val);
                }
            }
        }
        return result;
    }
}
