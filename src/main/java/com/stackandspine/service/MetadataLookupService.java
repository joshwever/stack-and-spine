package com.stackandspine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackandspine.model.MediaType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Looks up external media metadata:
 * - Books: OpenLibrary ISBN API (no API key required)
 * - Blu-Ray / DVD: TMDB API backed by Google Secret Manager (TMDB_API_KEY)
 */
@Service
public class MetadataLookupService {

    private static final Logger log = LoggerFactory.getLogger(MetadataLookupService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String tmdbApiKey;

    public MetadataLookupService(
            ObjectMapper objectMapper,
            @Value("${stackandspine.metadata.tmdb-api-key:NOT_CONFIGURED}") String tmdbApiKey) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.tmdbApiKey = tmdbApiKey;
    }

    public boolean isTmdbConfigured() {
        return tmdbApiKey != null
                && !tmdbApiKey.isBlank()
                && !"NOT_CONFIGURED".equalsIgnoreCase(tmdbApiKey.trim());
    }

    public Map<String, Object> lookup(MediaType type, String query) {
        if (query == null || query.isBlank()) {
            return Map.of("found", false, "message", "Enter an ISBN (for books) or title (for movies) to look up.");
        }
        if (type == MediaType.BOOK || type == MediaType.EBOOK) {
            return lookupBookByIsbn(query.trim());
        }
        return lookupMovieOnTmdb(query.trim());
    }

    private Map<String, Object> lookupBookByIsbn(String rawQuery) {
        String cleanIsbn = rawQuery.replaceAll("[^0-9Xx]", "");
        boolean looksLikeIsbn = cleanIsbn.length() == 10 || cleanIsbn.length() == 13;

        if (looksLikeIsbn) {
            try {
                String url = "https://openlibrary.org/api/books?bibkeys=ISBN:" + cleanIsbn + "&format=json&jscmd=data";
                String body = restClient.get().uri(url).retrieve().body(String.class);
                JsonNode root = objectMapper.readTree(body);
                JsonNode book = root.get("ISBN:" + cleanIsbn);
                if (book != null && !book.isMissingNode()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("found", true);
                    result.put("source", "OpenLibrary (ISBN)");
                    result.put("title", book.path("title").asText(""));

                    JsonNode authors = book.path("authors");
                    if (authors.isArray() && !authors.isEmpty()) {
                        result.put("creator", authors.get(0).path("name").asText(""));
                    }

                    String pubDate = book.path("publish_date").asText("");
                    String year = pubDate.replaceAll(".*?(\\d{4}).*", "$1");
                    if (year.matches("\\d{4}")) {
                        result.put("releaseYear", Integer.parseInt(year));
                    }

                    JsonNode subjects = book.path("subjects");
                    if (subjects.isArray() && !subjects.isEmpty()) {
                        result.put("genre", subjects.get(0).path("name").asText(""));
                    }

                    result.put("identifierCode", cleanIsbn);

                    if (book.has("number_of_pages")) {
                        result.put("attrKey1", "Pages");
                        result.put("attrVal1", String.valueOf(book.get("number_of_pages").asInt()));
                    }
                    JsonNode publishers = book.path("publishers");
                    if (publishers.isArray() && !publishers.isEmpty()) {
                        result.put("attrKey2", "Publisher");
                        result.put("attrVal2", publishers.get(0).path("name").asText(""));
                    }
                    return result;
                }
            } catch (Exception e) {
                log.warn("OpenLibrary ISBN lookup failed for {}: {}", cleanIsbn, e.getMessage());
            }
        }

        // Fallback to OpenLibrary Search API (supports both book titles and ISBNs missing from /api/books)
        try {
            String body = restClient.get()
                    .uri(
                            "https://openlibrary.org/search.json?q={q}&limit=1&fields=key,title,author_name,first_publish_year,isbn,number_of_pages_median,publisher,subject,first_sentence",
                            rawQuery)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            JsonNode docs = root.path("docs");
            if (!docs.isArray() || docs.isEmpty()) {
                return Map.of("found", false, "source", "OpenLibrary", "message", "No book found on OpenLibrary for '" + rawQuery + "'");
            }

            JsonNode doc = docs.get(0);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("source", "OpenLibrary (Search)");
            result.put("title", doc.path("title").asText(""));

            JsonNode authors = doc.path("author_name");
            if (authors.isArray() && !authors.isEmpty()) {
                result.put("creator", authors.get(0).asText(""));
            }
            if (doc.has("first_publish_year")) {
                result.put("releaseYear", doc.get("first_publish_year").asInt());
            }
            JsonNode subjects = doc.path("subject");
            if (subjects.isArray() && !subjects.isEmpty()) {
                result.put("genre", subjects.get(0).asText(""));
            }
            JsonNode isbns = doc.path("isbn");
            if (isbns.isArray() && !isbns.isEmpty()) {
                result.put("identifierCode", isbns.get(0).asText(""));
            } else if (looksLikeIsbn) {
                result.put("identifierCode", cleanIsbn);
            }
            if (doc.has("number_of_pages_median")) {
                result.put("attrKey1", "Pages");
                result.put("attrVal1", String.valueOf(doc.get("number_of_pages_median").asInt()));
            }
            JsonNode publishers = doc.path("publisher");
            if (publishers.isArray() && !publishers.isEmpty()) {
                result.put("attrKey2", "Publisher");
                result.put("attrVal2", publishers.get(0).asText(""));
            }
            String desc = fetchWorkDescription(doc.path("key").asText(""));
            if (desc == null || desc.isBlank()) {
                JsonNode firstSentence = doc.path("first_sentence");
                if (firstSentence.isArray() && !firstSentence.isEmpty()) {
                    desc = firstSentence.get(0).asText("");
                }
            }
            if (desc != null && !desc.isBlank()) {
                result.put("description", truncateDescription(desc));
            }
            return result;
        } catch (Exception e) {
            log.warn("OpenLibrary search failed for '{}': {}", rawQuery, e.getMessage());
            return Map.of("found", false, "source", "OpenLibrary", "message", "OpenLibrary lookup error: " + e.getMessage());
        }
    }

    private Map<String, Object> lookupMovieOnTmdb(String titleQuery) {
        if (!isTmdbConfigured()) {
            return Map.of(
                    "found", false,
                    "source", "TMDB (Secret Manager)",
                    "message", "TMDB secret 'tmdb-api-key' is wired via Secret Manager but still set to NOT_CONFIGURED. Add a real TMDB API key version to enable movie lookups!");
        }

        try {
            String body = restClient.get()
                    .uri("https://api.themoviedb.org/3/search/movie?api_key={key}&query={q}", tmdbApiKey.trim(), titleQuery)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return Map.of("found", false, "source", "TMDB", "message", "No movie found on TMDB for '" + titleQuery + "'");
            }

            JsonNode movie = results.get(0);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("source", "TMDB (Secret Manager)");
            result.put("title", movie.path("title").asText(""));
            String releaseDate = movie.path("release_date").asText("");
            if (releaseDate.length() >= 4) {
                result.put("releaseYear", Integer.parseInt(releaseDate.substring(0, 4)));
            }
            result.put("attrKey1", "TMDB ID");
            result.put("attrVal1", String.valueOf(movie.path("id").asInt()));
            result.put("attrKey2", "Rating");
            result.put("attrVal2", String.format("%.1f / 10", movie.path("vote_average").asDouble()));
            String overview = movie.path("overview").asText("");
            if (!overview.isBlank()) {
                result.put("description", truncateDescription(overview));
            }
            return result;
        } catch (Exception e) {
            log.warn("TMDB lookup failed for '{}': {}", titleQuery, e.getMessage());
            return Map.of("found", false, "source", "TMDB", "message", "TMDB lookup error: " + e.getMessage());
        }
    }

    private String fetchWorkDescription(String workKey) {
        if (workKey == null || !workKey.startsWith("/works/")) {
            return null;
        }
        try {
            String body = restClient.get()
                    .uri("https://openlibrary.org" + workKey + ".json")
                    .retrieve()
                    .body(String.class);
            JsonNode work = objectMapper.readTree(body);
            JsonNode descNode = work.get("description");
            if (descNode == null || descNode.isNull()) {
                return null;
            }
            if (descNode.isTextual()) {
                return descNode.asText();
            }
            if (descNode.isObject() && descNode.has("value")) {
                return descNode.get("value").asText();
            }
        } catch (Exception ignored) {
            // Optional enrichment; ignore transient failures
        }
        return null;
    }

    private String truncateDescription(String raw) {
        String cleaned = raw.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= 2000) {
            return cleaned;
        }
        int cut = cleaned.lastIndexOf(' ', 1997);
        return (cut > 1000 ? cleaned.substring(0, cut) : cleaned.substring(0, 1997)) + "...";
    }
}
