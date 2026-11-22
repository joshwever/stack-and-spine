package com.stackandspine.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a physical media item in the Stack and Spine catalog.
 * Designed as a clean document model that maps naturally to Google Cloud Firestore
 * (with common core fields + type-specific attributes for Books vs. Discs).
 */
public class MediaItem {

    private String id;

    @NotBlank(message = "Title is required")
    private String title;

    @NotNull(message = "Media type is required")
    private MediaType mediaType;

    /** Author for books, Director/Studio for Blu-Ray & DVD. */
    @NotBlank(message = "Creator (Author or Director) is required")
    private String creator;

    private Integer releaseYear;

    /** ISBN-13 for books or UPC/EAN barcode for Blu-Ray & DVD. */
    private String identifierCode;

    private String genre;

    /** Physical shelf/room location (e.g., "Living Room - Shelf B2"). */
    private String shelfLocation;

    /**
     * Flexible key-value attributes that differ by media type
     * (e.g., pages/publisher for Book; resolution/regionCode/discCount for Blu-Ray/DVD).
     * Firestore natively stores nested maps without requiring schema migrations!
     */
    private Map<String, String> attributes = new HashMap<>();

    /** GCS object path inside the private media bucket (e.g., "covers/lt-230813791.jpg"). */
    private String coverObjectPath;

    /** Short-lived V4 Signed URL generated at render time (not persisted in Firestore). */
    private String coverImageUrl;

    private Instant addedAt = Instant.now();

    public MediaItem() {
    }

    public MediaItem(
            String id,
            String title,
            MediaType mediaType,
            String creator,
            Integer releaseYear,
            String identifierCode,
            String genre,
            String shelfLocation,
            Map<String, String> attributes) {
        this.id = id;
        this.title = title;
        this.mediaType = mediaType;
        this.creator = creator;
        this.releaseYear = releaseYear;
        this.identifierCode = identifierCode;
        this.genre = genre;
        this.shelfLocation = shelfLocation;
        if (attributes != null) {
            this.attributes = new HashMap<>(attributes);
        }
        this.addedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public void setMediaType(MediaType mediaType) {
        this.mediaType = mediaType;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public Integer getReleaseYear() {
        return releaseYear;
    }

    public void setReleaseYear(Integer releaseYear) {
        this.releaseYear = releaseYear;
    }

    public String getIdentifierCode() {
        return identifierCode;
    }

    public void setIdentifierCode(String identifierCode) {
        this.identifierCode = identifierCode;
    }

    public String getGenre() {
        return genre;
    }

    public void setGenre(String genre) {
        this.genre = genre;
    }

    public String getShelfLocation() {
        return shelfLocation;
    }

    public void setShelfLocation(String shelfLocation) {
        this.shelfLocation = shelfLocation;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes != null ? attributes : new HashMap<>();
    }

    public String getCoverObjectPath() {
        return coverObjectPath;
    }

    public void setCoverObjectPath(String coverObjectPath) {
        this.coverObjectPath = coverObjectPath;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public void setCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(Instant addedAt) {
        this.addedAt = addedAt;
    }
}
