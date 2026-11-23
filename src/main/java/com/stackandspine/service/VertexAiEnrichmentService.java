package com.stackandspine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.stackandspine.model.MediaItem;
import com.stackandspine.model.MediaType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Calls Vertex AI Gemini (multimodal vision) using Application Default Credentials (ADC)
 * to extract structured media catalog metadata directly from a GCS photo URI (`gs://...`).
 */
@Service
public class VertexAiEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(VertexAiEnrichmentService.class);
    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private static final String PROMPT = """
            Analyze this photo of a physical media item (book cover, book spine, Blu-Ray case, or DVD case).
            Return ONLY a JSON object with these exact keys:
            {
              "title": "Exact title of the book or movie/show",
              "mediaType": "BOOK" or "EBOOK" or "BLU_RAY" or "DVD",
              "creator": "Primary author (for books) or director/studio (for Blu-Ray/DVD)",
              "releaseYear": 2020 (4-digit integer or null if unknown),
              "genre": "Primary genre (e.g., Sci-Fi, Fantasy, History, Action)",
              "description": "Concise 1-2 sentence synopsis of the book or movie/show",
              "identifierCode": "ISBN-13, ISBN-10, or UPC barcode digits if visible or confidently known, else null",
              "coverBox2d": [ymin, xmin, ymax, xmax] (tight bounding box around the book cover or Blu-Ray/DVD case in normalized 0-1000 coordinates, or null if the image is already tightly cropped),
              "attributes": {
                "Format": "e.g. Hardcover, Paperback, 4K UHD Blu-Ray, Standard DVD",
                "Publisher": "Publisher or Studio if known"
              }
            }
            Keep attributes values concise strings. If uncertain about a field, omit or set null, but always provide title, mediaType, and creator.
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CoverStorageService coverStorageService;
    private final String projectId;
    private final String location;
    private final String modelName;

    public VertexAiEnrichmentService(
            ObjectMapper objectMapper,
            CoverStorageService coverStorageService,
            @Value("${stackandspine.gcp.project-id}") String projectId,
            @Value("${stackandspine.gcp.vertex-location:global}") String location,
            @Value("${stackandspine.gcp.gemini-model:gemini-3-flash-preview}") String modelName) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.coverStorageService = coverStorageService;
        this.projectId = projectId;
        this.location = location;
        this.modelName = modelName;
    }

    public MediaItem extractMediaItem(String bucket, String objectName, String mimeType) throws IOException {
        String gcsUri = "gs://" + bucket + "/" + objectName;
        String safeMimeType = (mimeType != null && mimeType.startsWith("image/")) ? mimeType : "image/jpeg";

        String host = "global".equalsIgnoreCase(location)
                ? "aiplatform.googleapis.com"
                : location + "-aiplatform.googleapis.com";
        String endpoint = String.format(
                "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent",
                host, projectId, location, modelName);

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(
                                Map.of("fileData", Map.of(
                                        "mimeType", safeMimeType,
                                        "fileUri", gcsUri)),
                                Map.of("text", PROMPT)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "temperature", 0.1));

        String bearerToken = getAccessToken();
        log.info("Calling Vertex AI Gemini ({}) on {}", modelName, gcsUri);

        String responseJson = restClient.post()
                .uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(responseJson);
        String modelText = root.path("candidates").path(0)
                .path("content").path("parts").path(0)
                .path("text").asText("");

        if (modelText.isBlank()) {
            throw new IllegalStateException("Vertex AI Gemini returned empty response for " + gcsUri);
        }

        // Strip markdown fences if any
        String cleanedJson = modelText.trim()
                .replaceAll("^```json\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();

        JsonNode extracted = objectMapper.readTree(cleanedJson);
        return buildMediaItem(bucket, objectName, extracted);
    }

    private MediaItem buildMediaItem(String bucket, String objectName, JsonNode extracted) {
        String id = "ai-" + shortHash(objectName);
        String title = textOrDefault(extracted, "title", "Unidentified Media Item");
        String creator = textOrDefault(extracted, "creator", "Unknown Creator");
        MediaType mediaType = parseMediaType(extracted.path("mediaType").asText("BOOK"));

        Integer releaseYear = null;
        if (extracted.has("releaseYear") && extracted.get("releaseYear").isNumber()) {
            releaseYear = extracted.get("releaseYear").asInt();
        }

        String genre = textOrNull(extracted, "genre");
        String identifierCode = textOrNull(extracted, "identifierCode");

        Map<String, String> attributes = new LinkedHashMap<>();
        JsonNode attrNode = extracted.get("attributes");
        if (attrNode != null && attrNode.isObject()) {
            for (Map.Entry<String, JsonNode> entry : attrNode.properties()) {
                if (entry.getValue() != null && !entry.getValue().isNull()) {
                    String val = entry.getValue().asText("").trim();
                    if (!val.isEmpty()) {
                        attributes.put(entry.getKey(), val);
                    }
                }
            }
        }
        attributes.put("AI Enriched", modelName);

        MediaItem item = new MediaItem(
                id,
                title,
                mediaType,
                creator,
                releaseYear,
                identifierCode,
                genre,
                "AI Scan Inbox",
                attributes);
        item.setDescription(textOrNull(extracted, "description"));
        item.setCoverObjectPath(objectName);
        JsonNode box = extracted.get("coverBox2d");
        if (box != null && box.isArray() && box.size() == 4
                && box.get(0).isNumber() && box.get(1).isNumber()
                && box.get(2).isNumber() && box.get(3).isNumber()) {
            int ymin = box.get(0).asInt();
            int xmin = box.get(1).asInt();
            int ymax = box.get(2).asInt();
            int xmax = box.get(3).asInt();
            if (ymin >= 0 && xmin >= 0 && ymax <= 1000 && xmax <= 1000 && ymax > ymin && xmax > xmin) {
                coverStorageService.cropAndSaveCover(bucket, objectName, id, ymin, xmin, ymax, xmax)
                        .ifPresent(item::setCoverObjectPath);
            }
        }
        item.setAddedAt(Instant.now());
        return item;
    }

    private String getAccessToken() throws IOException {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                .createScoped(List.of(CLOUD_PLATFORM_SCOPE));
        credentials.refreshIfExpired();
        AccessToken token = credentials.getAccessToken();
        if (token == null) {
            credentials.refresh();
            token = credentials.getAccessToken();
        }
        return token.getTokenValue();
    }

    private MediaType parseMediaType(String raw) {
        if (raw == null) {
            return MediaType.BOOK;
        }
        try {
            return MediaType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MediaType.BOOK;
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        String val = child.asText("").trim();
        return val.isEmpty() || "null".equalsIgnoreCase(val) ? null : val;
    }

    private String textOrDefault(JsonNode node, String field, String fallback) {
        String val = textOrNull(node, field);
        return val != null ? val : fallback;
    }

    private String shortHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }
}
