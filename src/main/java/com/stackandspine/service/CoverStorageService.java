package com.stackandspine.service;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Manages cover art / spine photo uploads to a private Google Cloud Storage bucket
 * and generates short-lived V4 Signed URLs using keyless IAM signBlob credentials.
 */
@Service
public class CoverStorageService {

    private static final Logger log = LoggerFactory.getLogger(CoverStorageService.class);
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    private final String projectId;
    private final String bucketName;
    private final String signerServiceAccount;
    private final boolean gcsEnabled;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile Storage storageClient;
    private volatile ServiceAccountSigner cachedSigner;

    public CoverStorageService(
            @Value("${stackandspine.gcp.project-id}") String projectId,
            @Value("${stackandspine.gcp.media-bucket:}") String bucketName,
            @Value("${stackandspine.gcp.signer-service-account:}") String signerServiceAccount,
            @Value("${stackandspine.storage:firestore}") String storageMode) {
        this.projectId = projectId;
        this.bucketName = bucketName;
        this.signerServiceAccount = signerServiceAccount;
        this.gcsEnabled = "firestore".equalsIgnoreCase(storageMode)
                && bucketName != null
                && !bucketName.isBlank();
    }

    public boolean isEnabled() {
        return gcsEnabled;
    }

    /**
     * Uploads an image file to the private GCS bucket under `covers/<itemId>-<uuid>.<ext>`
     * and returns the GCS object path to store in Firestore.
     */
    public String uploadCover(String itemId, MultipartFile file) throws IOException {
        if (!gcsEnabled) {
            throw new IllegalStateException("GCS cover uploads require stackandspine.storage=firestore and a configured media bucket.");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded cover file is empty.");
        }

        String contentType = file.getContentType() != null
                ? file.getContentType().toLowerCase(Locale.ROOT)
                : "application/octet-stream";
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported image type: " + contentType + ". Use JPEG, PNG, WebP, or GIF.");
        }

        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };

        String safeItemId = itemId.replaceAll("[^a-zA-Z0-9_-]", "_");
        String objectPath = "covers/" + safeItemId + "-" + UUID.randomUUID().toString().substring(0, 8) + extension;

        BlobId blobId = BlobId.of(bucketName, objectPath);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .setCacheControl("private, max-age=3600")
                .build();

        getStorage().create(blobInfo, file.getBytes());
        log.info("Uploaded cover image to gs://{}/{}", bucketName, objectPath);
        return objectPath;
    }

    /**
     * Uploads a cover/spine photo to the `inbox/` prefix in the private GCS bucket,
     * triggering the Eventarc -> Vertex AI Gemini asynchronous enrichment pipeline.
     */
    public String uploadInboxPhoto(MultipartFile file) throws IOException {
        if (!gcsEnabled) {
            throw new IllegalStateException("AI Photo Scan requires stackandspine.storage=firestore and a configured media bucket.");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded photo is empty.");
        }

        String contentType = file.getContentType() != null
                ? file.getContentType().toLowerCase(Locale.ROOT)
                : "application/octet-stream";
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported image type: " + contentType + ". Use JPEG, PNG, WebP, or GIF.");
        }

        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };

        String objectPath = "inbox/" + UUID.randomUUID() + extension;
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectPath))
                .setContentType(contentType)
                .setCacheControl("private, max-age=3600")
                .build();

        getStorage().create(blobInfo, file.getBytes());
        log.info("Uploaded AI scan photo to gs://{}/{} (Eventarc trigger will process)", bucketName, objectPath);
        return objectPath;
    }

    /**
     * Returns the OpenLibrary Covers API URL for an ISBN-10 or ISBN-13, or null if not a valid ISBN.
     */
    public String openLibraryCoverUrl(String rawIsbn) {
        if (rawIsbn == null || rawIsbn.isBlank()) {
            return null;
        }
        String clean = rawIsbn.replaceAll("[^0-9Xx]", "");
        if (clean.length() != 10 && clean.length() != 13) {
            return null;
        }
        return "https://covers.openlibrary.org/b/isbn/" + clean + "-M.jpg?default=false";
    }

    /**
     * Downloads a book's cover image from OpenLibrary Covers API and archives it into
     * the private GCS bucket under `covers/<itemId>-<uuid>.jpg`.
     */
    public Optional<String> fetchAndUploadOpenLibraryCover(String itemId, String rawIsbn) {
        if (!gcsEnabled) {
            return Optional.empty();
        }
        String coverUrl = openLibraryCoverUrl(rawIsbn);
        if (coverUrl == null) {
            return Optional.empty();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(coverUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "StackAndSpine/1.0")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length < 500) {
                log.info("No OpenLibrary cover available at {} (HTTP {})", coverUrl, response.statusCode());
                return Optional.empty();
            }

            String contentType = response.headers()
                    .firstValue("content-type")
                    .map(v -> v.split(";")[0].trim().toLowerCase(Locale.ROOT))
                    .orElse("image/jpeg");
            if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
                return Optional.empty();
            }

            String extension = switch (contentType) {
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                case "image/gif" -> ".gif";
                default -> ".jpg";
            };

            String safeItemId = itemId.replaceAll("[^a-zA-Z0-9_-]", "_");
            String objectPath = "covers/" + safeItemId + "-" + UUID.randomUUID().toString().substring(0, 8) + extension;

            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectPath))
                    .setContentType(contentType)
                    .setCacheControl("private, max-age=3600")
                    .build();

            getStorage().create(blobInfo, response.body());
            log.info("Archived OpenLibrary cover ({} bytes) to gs://{}/{}", response.body().length, bucketName, objectPath);
            return Optional.of(objectPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to archive OpenLibrary cover for item {} (ISBN {}): {}", itemId, rawIsbn, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Generates a 15-minute V4 Signed GET URL for a private GCS object without using
     * any static private key file (delegates signing to IAM Credentials API signBlob).
     */
    public String generateSignedReadUrl(String objectPath) {
        if (!gcsEnabled || objectPath == null || objectPath.isBlank()) {
            return null;
        }
        try {
            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, objectPath)).build();
            ServiceAccountSigner signer = getSigner();
            URL signedUrl = getStorage().signUrl(
                    blobInfo,
                    15,
                    TimeUnit.MINUTES,
                    Storage.SignUrlOption.withV4Signature(),
                    Storage.SignUrlOption.signWith(signer));
            return signedUrl.toString();
        } catch (Exception e) {
            log.warn("Could not generate V4 Signed URL for gs://{}/{}: {}", bucketName, objectPath, e.getMessage());
            return null;
        }
    }

    private Storage getStorage() {
        if (storageClient == null) {
            synchronized (this) {
                if (storageClient == null) {
                    storageClient = StorageOptions.newBuilder()
                            .setProjectId(projectId)
                            .build()
                            .getService();
                }
            }
        }
        return storageClient;
    }

    private ServiceAccountSigner getSigner() throws IOException {
        if (cachedSigner == null) {
            synchronized (this) {
                if (cachedSigner == null) {
                    GoogleCredentials adc = GoogleCredentials.getApplicationDefault();
                    if (adc instanceof ServiceAccountSigner nativeSigner) {
                        cachedSigner = nativeSigner;
                    } else {
                        // Keyless signing on Cloud Run (ComputeEngineCredentials) and local ADC:
                        // Uses iamcredentials.googleapis.com signBlob via ImpersonatedCredentials.
                        cachedSigner = ImpersonatedCredentials.create(
                                adc,
                                signerServiceAccount,
                                null,
                                List.of("https://www.googleapis.com/auth/devstorage.read_only"),
                                300);
                    }
                }
            }
        }
        return cachedSigner;
    }
}
