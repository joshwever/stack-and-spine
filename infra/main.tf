locals {
  service_name      = "stack-and-spine"
  media_bucket_name = "stack-and-spine-media-${var.project_number}"
  image_url         = "${var.region}-docker.pkg.dev/${var.project_id}/${local.service_name}/${local.service_name}:${var.image_tag}"
  iap_service_agent = "service-${var.project_number}@gcp-sa-iap.iam.gserviceaccount.com"

  enabled_apis = toset([
    "aiplatform.googleapis.com",
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "eventarc.googleapis.com",
    "firestore.googleapis.com",
    "iamcredentials.googleapis.com",
    "iap.googleapis.com",
    "pubsub.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "storage.googleapis.com",
  ])
}

# 1. Enable Required Google Cloud Service APIs
resource "google_project_service" "apis" {
  for_each           = local.enabled_apis
  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

# 2. Serverless NoSQL Database: Cloud Firestore (Native Mode)
resource "google_firestore_database" "default" {
  project     = var.project_id
  name        = "(default)"
  location_id = var.firestore_location
  type        = "FIRESTORE_NATIVE"

  depends_on = [google_project_service.apis["firestore.googleapis.com"]]
}

# 3. Regional Container Registry: Artifact Registry
resource "google_artifact_registry_repository" "app" {
  project       = var.project_id
  location      = var.region
  repository_id = local.service_name
  description   = "Stack and Spine container images"
  format        = "DOCKER"

  depends_on = [google_project_service.apis["artifactregistry.googleapis.com"]]
}

# 4. Dedicated Least-Privilege Runtime IAM Service Account
resource "google_service_account" "cloud_run_runtime" {
  project      = var.project_id
  account_id   = "stack-and-spine-run"
  display_name = "Stack and Spine Cloud Run Service Account"
  description  = "Least-privilege runtime identity for Stack and Spine on Cloud Run"
}

# Grant Firestore read/write access (roles/datastore.user)
resource "google_project_iam_member" "runtime_firestore_user" {
  project = var.project_id
  role    = "roles/datastore.user"
  member  = "serviceAccount:${google_service_account.cloud_run_runtime.email}"
}

# Allow the Service Account to sign V4 GCS Signed URLs via IAM signBlob (keyless!)
resource "google_service_account_iam_member" "runtime_self_token_creator" {
  service_account_id = google_service_account.cloud_run_runtime.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${google_service_account.cloud_run_runtime.email}"
}

# 5. Private Cloud Storage Bucket for Cover Art & Spine Photos
resource "google_storage_bucket" "media" {
  project                     = var.project_id
  name                        = local.media_bucket_name
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  depends_on = [google_project_service.apis["storage.googleapis.com"]]
}

# Resource-scoped bucket IAM: grant objectAdmin ONLY on this bucket
resource "google_storage_bucket_iam_member" "runtime_media_admin" {
  bucket = google_storage_bucket.media.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.cloud_run_runtime.email}"
}

# 6. Secret Manager Secret for External Metadata API (TMDB)
resource "google_secret_manager_secret" "tmdb_api_key" {
  project   = var.project_id
  secret_id = "tmdb-api-key"

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis["secretmanager.googleapis.com"]]
}

# Secret-scoped IAM: grant secretAccessor ONLY on tmdb-api-key
resource "google_secret_manager_secret_iam_member" "runtime_tmdb_accessor" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.tmdb_api_key.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.cloud_run_runtime.email}"
}

# 7. Serverless Compute: Cloud Run Service with Native IAP Enabled
resource "google_cloud_run_v2_service" "app" {
  project     = var.project_id
  name        = local.service_name
  location    = var.region
  iap_enabled = true

  template {
    service_account = google_service_account.cloud_run_runtime.email

    containers {
      image = local.image_url

      env {
        name  = "STACKANDSPINE_STORAGE"
        value = "firestore"
      }
      env {
        name  = "GOOGLE_CLOUD_PROJECT"
        value = var.project_id
      }
      env {
        name  = "STACKANDSPINE_MEDIA_BUCKET"
        value = google_storage_bucket.media.name
      }
      env {
        name  = "STACKANDSPINE_SIGNER_SA"
        value = google_service_account.cloud_run_runtime.email
      }
      env {
        name = "TMDB_API_KEY"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.tmdb_api_key.secret_id
            version = "latest"
          }
        }
      }
    }
  }

  depends_on = [
    google_project_service.apis["run.googleapis.com"],
    google_project_service.apis["iap.googleapis.com"],
    google_secret_manager_secret_iam_member.runtime_tmdb_accessor,
  ]
}

# Allow the Google-managed IAP Service Agent to invoke the private Cloud Run service
resource "google_cloud_run_v2_service_iam_member" "iap_invoker" {
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.app.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${local.iap_service_agent}"
}

# Grant ONLY the authorized user access through Identity-Aware Proxy
resource "google_iap_web_cloud_run_service_iam_member" "authorized_user" {
  project                = var.project_id
  location               = var.region
  cloud_run_service_name = google_cloud_run_v2_service.app.name
  role                   = "roles/iap.httpsResourceAccessor"
  member                 = "user:${var.authorized_user_email}"
}

# 8. Phase 6 — Event-Driven AI Enrichment (GCS -> Eventarc -> Private Worker -> Vertex AI Gemini)
resource "google_project_iam_member" "runtime_vertex_ai_user" {
  project = var.project_id
  role    = "roles/aiplatform.user"
  member  = "serviceAccount:${google_service_account.cloud_run_runtime.email}"
}

resource "google_project_iam_member" "runtime_eventarc_receiver" {
  project = var.project_id
  role    = "roles/eventarc.eventReceiver"
  member  = "serviceAccount:${google_service_account.cloud_run_runtime.email}"
}

resource "google_project_iam_member" "gcs_pubsub_publisher" {
  project = var.project_id
  role    = "roles/pubsub.publisher"
  member  = "serviceAccount:service-${var.project_number}@gs-project-accounts.iam.gserviceaccount.com"
}

resource "google_project_iam_member" "eventarc_service_agent" {
  project = var.project_id
  role    = "roles/eventarc.serviceAgent"
  member  = "serviceAccount:service-${var.project_number}@gcp-sa-eventarc.iam.gserviceaccount.com"
}

resource "google_cloud_run_v2_service" "enricher" {
  project  = var.project_id
  name     = "stack-and-spine-enricher"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_INTERNAL_ONLY"

  template {
    service_account = google_service_account.cloud_run_runtime.email

    containers {
      image = local.image_url

      env {
        name  = "STACKANDSPINE_STORAGE"
        value = "firestore"
      }
      env {
        name  = "GOOGLE_CLOUD_PROJECT"
        value = var.project_id
      }
      env {
        name  = "STACKANDSPINE_MEDIA_BUCKET"
        value = google_storage_bucket.media.name
      }
      env {
        name  = "STACKANDSPINE_SIGNER_SA"
        value = google_service_account.cloud_run_runtime.email
      }
    }
  }

  depends_on = [google_project_service.apis["run.googleapis.com"]]
}

resource "google_cloud_run_v2_service_iam_member" "enricher_invoker" {
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.enricher.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.cloud_run_runtime.email}"
}

resource "google_eventarc_trigger" "gcs_inbox" {
  project         = var.project_id
  name            = "stack-and-spine-inbox-trigger"
  location        = var.region
  service_account = google_service_account.cloud_run_runtime.email

  matching_criteria {
    attribute = "type"
    value     = "google.cloud.storage.object.v1.finalized"
  }
  matching_criteria {
    attribute = "bucket"
    value     = google_storage_bucket.media.name
  }

  destination {
    cloud_run_service {
      service = google_cloud_run_v2_service.enricher.name
      region  = var.region
      path    = "/events/gcs-inbox"
    }
  }

  depends_on = [
    google_project_service.apis["eventarc.googleapis.com"],
    google_project_iam_member.gcs_pubsub_publisher,
    google_project_iam_member.eventarc_service_agent,
    google_cloud_run_v2_service_iam_member.enricher_invoker,
  ]
}
