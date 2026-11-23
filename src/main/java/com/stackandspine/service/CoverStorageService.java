package com.stackandspine.service;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import javax.imageio.ImageIO;
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
     * Downloads an inbox photo from GCS, crops it to the Gemini-detected normalized [0..1000]
     * bounding box [ymin, xmin, ymax, xmax], and saves the clean cropped JPEG under `covers/`.
     */
    public Optional<String> cropAndSaveCover(
            String sourceBucket,
            String sourceObjectPath,
            String itemId,
            int ymin,
            int xmin,
            int ymax,
            int xmax) {
        if (!gcsEnabled) {
            return Optional.empty();
        }
        try {
            byte[] rawBytes = getStorage().readAllBytes(BlobId.of(sourceBucket, sourceObjectPath));
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (decoded == null) {
                log.warn("Could not decode image gs://{}/{} for auto-cropping", sourceBucket, sourceObjectPath);
                return Optional.empty();
            }

            // Apply EXIF Orientation (e.g. Pixel portrait photos stored sideways with Orientation=6)
            // so the pixel buffer matches the upright orientation Gemini used for coverBox2d.
            int exifOrientation = readJpegExifOrientation(rawBytes);
            BufferedImage source = applyExifOrientation(decoded, exifOrientation);

            int imgW = source.getWidth();
            int imgH = source.getHeight();

            // Convert Gemini normalized 0..1000 coords to pixels, with ~1% margin clamped to image bounds
            int x1 = Math.max(0, (int) Math.floor(((xmin - 10) / 1000.0) * imgW));
            int y1 = Math.max(0, (int) Math.floor(((ymin - 10) / 1000.0) * imgH));
            int x2 = Math.min(imgW, (int) Math.ceil(((xmax + 10) / 1000.0) * imgW));
            int y2 = Math.min(imgH, (int) Math.ceil(((ymax + 10) / 1000.0) * imgH));

            int cropW = x2 - x1;
            int cropH = y2 - y1;
            if (cropW < imgW * 0.10 || cropH < imgH * 0.10) {
                log.info("Skipping auto-crop for {} (box too small: {}x{} of {}x{})", sourceObjectPath, cropW, cropH, imgW, imgH);
                return Optional.empty();
            }

            BufferedImage sub = source.getSubimage(x1, y1, cropW, cropH);
            // Convert to TYPE_INT_RGB so ImageIO JPEG writer never fails on alpha channels
            BufferedImage rgb = new BufferedImage(cropW, cropH, BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(sub, 0, 0, null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", out);

            String safeItemId = itemId.replaceAll("[^a-zA-Z0-9_-]", "_");
            String destPath = "covers/" + safeItemId + "-cropped.jpg";
            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, destPath))
                    .setContentType("image/jpeg")
                    .setCacheControl("private, max-age=3600")
                    .build();

            getStorage().create(blobInfo, out.toByteArray());
            log.info("Auto-cropped cover (exif={}, {}x{} -> {}x{}) saved to gs://{}/{}",
                    exifOrientation, imgW, imgH, cropW, cropH, bucketName, destPath);
            return Optional.of(destPath);
        } catch (Exception e) {
            log.warn("Auto-crop failed for gs://{}/{}: {}", sourceBucket, sourceObjectPath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reads the TIFF EXIF Orientation tag (0x0112) from a JPEG byte array.
     * Returns 1 (normal) if not JPEG or no EXIF Orientation tag is present.
     */
    private int readJpegExifOrientation(byte[] data) {
        if (data == null || data.length < 12 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int pos = 2;
        while (pos + 4 < data.length) {
            if ((data[pos] & 0xFF) != 0xFF) {
                return 1;
            }
            int marker = data[pos + 1] & 0xFF;
            pos += 2;
            if (marker == 0xD9 || marker == 0xDA) {
                return 1; // EOI or SOS
            }
            int segLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            if (segLen < 2 || pos + segLen > data.length) {
                return 1;
            }
            if (marker == 0xE1 && segLen >= 14
                    && data[pos + 2] == 'E' && data[pos + 3] == 'x'
                    && data[pos + 4] == 'i' && data[pos + 5] == 'f'
                    && data[pos + 6] == 0 && data[pos + 7] == 0) {
                int tiffStart = pos + 8;
                boolean littleEndian = data[tiffStart] == 'I' && data[tiffStart + 1] == 'I';
                int ifdOffset = readUInt32(data, tiffStart + 4, littleEndian);
                int ifdStart = tiffStart + ifdOffset;
                if (ifdStart + 2 > data.length) {
                    return 1;
                }
                int entryCount = readUInt16(data, ifdStart, littleEndian);
                for (int i = 0; i < entryCount; i++) {
                    int entryPos = ifdStart + 2 + i * 12;
                    if (entryPos + 12 > data.length) {
                        break;
                    }
                    int tag = readUInt16(data, entryPos, littleEndian);
                    if (tag == 0x0112) {
                        return readUInt16(data, entryPos + 8, littleEndian);
                    }
                }
                return 1;
            }
            pos += segLen;
        }
        return 1;
    }

    private int readUInt16(byte[] data, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        }
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private int readUInt32(byte[] data, int offset, boolean littleEndian) {
        if (littleEndian) {
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 24);
        }
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private BufferedImage applyExifOrientation(BufferedImage img, int orientation) {
        int w = img.getWidth();
        int h = img.getHeight();
        return switch (orientation) {
            case 3 -> { // 180 degrees
                BufferedImage rotated = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rotated.createGraphics();
                AffineTransform tx = new AffineTransform();
                tx.translate(w, h);
                tx.rotate(Math.PI);
                g.drawImage(img, tx, null);
                g.dispose();
                yield rotated;
            }
            case 6 -> { // 90 degrees clockwise (standard phone portrait)
                BufferedImage rotated = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rotated.createGraphics();
                AffineTransform tx = new AffineTransform();
                tx.translate(h, 0);
                tx.rotate(Math.PI / 2);
                g.drawImage(img, tx, null);
                g.dispose();
                yield rotated;
            }
            case 8 -> { // 270 degrees clockwise (90 degrees CCW)
                BufferedImage rotated = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rotated.createGraphics();
                AffineTransform tx = new AffineTransform();
                tx.translate(0, w);
                tx.rotate(-Math.PI / 2);
                g.drawImage(img, tx, null);
                g.dispose();
                yield rotated;
            }
            default -> img;
        };
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
