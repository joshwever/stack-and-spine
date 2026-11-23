# Stack & Spine

A personal mixed-media catalog (**Books, Blu-Rays, and DVDs**) built with **Java 25 + Spring Boot 3.5 + Thymeleaf/HTMX** and designed as a hands-on journey from on-prem application architecture to **Google Cloud Platform (GCP)**.

---

## Architecture Overview

| Layer | Technology / GCP Service | Purpose |
| :--- | :--- | :--- |
| **Application** | Java 25 (OpenJDK 25), Spring Boot 3.5.7, Gradle 9.2.1 (Groovy DSL) | Server-rendered UI (Thymeleaf + Tailwind + HTMX) and JSON API (`/api/items`, `/api/lookup`). |
| **Database** | **Cloud Firestore** (Native Mode, `nam5` Free Tier) | Serverless NoSQL document store (`media_items` collection) with flexible per-media `attributes` maps. Seeded with 139 books from LibraryThing (`librarythingBookList.json`). |
| **Container Build** | **Google Jib** (`./gradlew jib`) + **Artifact Registry** | Daemonless, layered OCI image builds on `eclipse-temurin:25-jre` pushed directly to `us-central1-docker.pkg.dev`. |
| **Compute** | **Cloud Run** (`us-central1`) | Serverless container hosting with automatic HTTPS, `$PORT` binding, and scale-to-zero. |
| **Media Assets** | **Cloud Storage (GCS)** (`us-central1`) | Private bucket (`uniform_bucket_level_access` + `public_access_prevention`) for uploaded and OpenLibrary-archived covers, served via 15-minute **keyless V4 Signed URLs** (`iamcredentials.googleapis.com`). |
| **Secrets** | **Secret Manager** | Stores `tmdb-api-key` and injects it into Cloud Run via `--set-secrets` with secret-scoped IAM. |
| **Zero-Trust Auth** | **Identity-Aware Proxy (IAP)** | Native Cloud Run IAP enforcing Google Sign-In at Google's edge (`roles/iap.httpsResourceAccessor`) and forwarding `X-Goog-Authenticated-User-Email`. |
| **Event-Driven AI** | **Eventarc** + **Pub/Sub** + **Vertex AI (`gemini-3-flash-preview`)** | Asynchronous **AI Photo Scan** pipeline: uploading a cover/spine photo to GCS `inbox/` triggers an internal-only Cloud Run worker (`stack-and-spine-enricher`) that uses Gemini Vision to auto-catalog the item in Firestore. |
| **IaC & CI/CD** | **Terraform** (`infra/`) + **Cloud Build** (`cloudbuild.yaml`) | Declarative GCP infrastructure (with `import` blocks) and serverless test/build/deploy pipeline using a dedicated least-privilege builder Service Account. |

---

## Key Features

- **Live Search & Filtering**: Instant HTMX search by title, author/director, ISBN/UPC, or genre, plus media-type filtering (`BOOK`, `BLU_RAY`, `DVD`).
- **LibraryThing Initial Seed**: [`LibraryThingSeedLoader`](src/main/java/com/stackandspine/repository/LibraryThingSeedLoader.java) parses `src/main/resources/librarythingBookList.json` to seed both the local in-memory store and an empty Firestore collection atomically via `WriteBatch`.
- **Metadata Auto-Fill (`/api/lookup`)**:
  - **Books**: OpenLibrary ISBN & Search API (no API key required).
  - **Blu-Rays / DVDs**: TMDB Search API backed by Google Secret Manager (`tmdb-api-key`).
- **Cover Art Management**:
  - Automatic OpenLibrary cover display for books with ISBNs.
  - One-click **Upload Cover** or **Fetch to GCS** to archive covers into the private GCS bucket and serve them via V4 Signed URLs.
- **AI Photo Scan (Event-Driven Enrichment)**:
  - Click **AI Scan** from desktop or mobile to upload a photo of a book cover, Blu-Ray/DVD case, or spine to GCS `inbox/`.
  - **Eventarc** (`google.cloud.storage.object.v1.finalized`) delivers a CloudEvent to the internal-only `stack-and-spine-enricher` worker (`POST /events/gcs-inbox`), which calls **Vertex AI Gemini (`gemini-3-flash-preview`, `global`)** directly on the `gs://` URI and writes the extracted `MediaItem` into Firestore with the photo attached as its cover.

---

## Local Development

### Prerequisites
- **Java 25** (`openjdk-25-jdk`)
- **Google Cloud CLI (`gcloud`)** authenticated with both CLI and Application Default Credentials:
  ```bash
  gcloud auth login
  gcloud auth application-default login --no-launch-browser
  ```

### Running Locally
- **Against live GCP Firestore** (default):
  ```bash
  ./gradlew bootRun
  ```
- **Offline / In-Memory mode** (no GCP network calls):
  ```bash
  STACKANDSPINE_STORAGE=memory ./gradlew bootRun
  ```
- **Run Tests**:
  ```bash
  ./gradlew test
  ```

Open `http://localhost:8080` in your browser.

---

## Deployment & CI/CD

### Manual Container Push & Deploy (via Jib)
```bash
./gradlew test jib
gcloud run deploy stack-and-spine \
  --image="us-central1-docker.pkg.dev/project-f2e57f3d-860e-486f-a44/stack-and-spine/stack-and-spine:latest" \
  --region=us-central1 \
  --project=project-f2e57f3d-860e-486f-a44
```

### Serverless Cloud Build Pipeline (`cloudbuild.yaml`)
```bash
gcloud builds submit . \
  --config=cloudbuild.yaml \
  --region=us-central1 \
  --service-account="projects/project-f2e57f3d-860e-486f-a44/serviceAccounts/stack-and-spine-builder@project-f2e57f3d-860e-486f-a44.iam.gserviceaccount.com" \
  --project=project-f2e57f3d-860e-486f-a44
```

### Infrastructure as Code (`infra/`)
All GCP resources are declared in [`infra/main.tf`](infra/main.tf) with declarative import blocks in [`infra/imports.tf`](infra/imports.tf):
```bash
cd infra
terraform init
terraform plan
```

---

## Project Structure

```text
├── build.gradle                         # Java 25, Spring Boot 3.5.7, GCP BOM, Jib config
├── cloudbuild.yaml                      # Serverless Cloud Build test + Jib + Cloud Run deploy
├── infra/                               # Terraform IaC (APIs, Firestore, GCS, Secret Manager, IAM, Cloud Run + IAP)
│   ├── versions.tf
│   ├── variables.tf
│   ├── main.tf
│   ├── imports.tf
│   └── outputs.tf
└── src/main/
    ├── java/com/stackandspine/
    │   ├── config/                      # FirestoreConfig (ADC)
    │   ├── model/                       # MediaItem, MediaType
    │   ├── repository/                  # MediaRepository, InMemory, Firestore, LibraryThingSeedLoader
    │   ├── service/                     # CoverStorageService (GCS + V4 Signed URLs), MetadataLookupService
    │   └── web/                         # CatalogController
    └── resources/
        ├── application.yml
        ├── librarythingBookList.json    # Initial 139-book dataset
        ├── static/js/catalog.js
        └── templates/                   # Thymeleaf index + fragments
```
