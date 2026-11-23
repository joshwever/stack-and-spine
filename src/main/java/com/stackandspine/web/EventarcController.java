package com.stackandspine.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackandspine.model.MediaItem;
import com.stackandspine.repository.MediaRepository;
import com.stackandspine.service.VertexAiEnrichmentService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives CloudEvents delivered by Google Cloud Eventarc when new objects are finalized
 * in the private media GCS bucket. Only processes photos uploaded under `inbox/`.
 */
@RestController
public class EventarcController {

    private static final Logger log = LoggerFactory.getLogger(EventarcController.class);

    private final VertexAiEnrichmentService enrichmentService;
    private final MediaRepository mediaRepository;

    public EventarcController(
            VertexAiEnrichmentService enrichmentService,
            MediaRepository mediaRepository) {
        this.enrichmentService = enrichmentService;
        this.mediaRepository = mediaRepository;
    }

    @PostMapping("/events/gcs-inbox")
    public ResponseEntity<Map<String, Object>> handleGcsObjectFinalized(
            @RequestHeader(name = "ce-type", required = false) String ceType,
            @RequestHeader(name = "ce-subject", required = false) String ceSubject,
            @RequestBody JsonNode payload) {

        String bucket = payload.path("bucket").asText("");
        String name = payload.path("name").asText("");
        String contentType = payload.path("contentType").asText("image/jpeg");

        log.info("Received Eventarc CloudEvent type='{}' subject='{}' object='gs://{}/{}'",
                ceType, ceSubject, bucket, name);

        if (!name.startsWith("inbox/")) {
            log.debug("Ignoring non-inbox GCS object '{}'", name);
            return ResponseEntity.ok(Map.of("status", "ignored", "object", name));
        }

        try {
            MediaItem item = enrichmentService.extractMediaItem(bucket, name, contentType);
            mediaRepository.save(item);
            log.info("AI-enriched and saved MediaItem id='{}' title='{}' ({}) from gs://{}/{}",
                    item.getId(), item.getTitle(), item.getMediaType(), bucket, name);
            return ResponseEntity.ok(Map.of(
                    "status", "enriched",
                    "id", item.getId(),
                    "title", item.getTitle(),
                    "mediaType", item.getMediaType().name()));
        } catch (Exception e) {
            log.error("Failed to enrich GCS inbox object gs://{}/{}: {}", bucket, name, e.getMessage(), e);
            // Return 500 so Eventarc knows delivery failed (can retry if configured)
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}
