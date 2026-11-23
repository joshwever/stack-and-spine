# Declarative Terraform import blocks (Terraform >= 1.5)
# Because we provisioned these resources interactively via `gcloud` in Parts 1–4,
# these import blocks let `terraform plan` / `terraform apply` adopt the existing
# GCP resources into Terraform state cleanly without recreating or interrupting them!

import {
  to = google_firestore_database.default
  id = "projects/project-f2e57f3d-860e-486f-a44/databases/(default)"
}

import {
  to = google_artifact_registry_repository.app
  id = "projects/project-f2e57f3d-860e-486f-a44/locations/us-central1/repositories/stack-and-spine"
}

import {
  to = google_service_account.cloud_run_runtime
  id = "projects/project-f2e57f3d-860e-486f-a44/serviceAccounts/stack-and-spine-run@project-f2e57f3d-860e-486f-a44.iam.gserviceaccount.com"
}

import {
  to = google_storage_bucket.media
  id = "stack-and-spine-media-212550439760"
}

import {
  to = google_secret_manager_secret.tmdb_api_key
  id = "projects/project-f2e57f3d-860e-486f-a44/secrets/tmdb-api-key"
}

import {
  to = google_cloud_run_v2_service.app
  id = "projects/project-f2e57f3d-860e-486f-a44/locations/us-central1/services/stack-and-spine"
}

import {
  to = google_cloud_run_v2_service.enricher
  id = "projects/project-f2e57f3d-860e-486f-a44/locations/us-central1/services/stack-and-spine-enricher"
}

import {
  to = google_eventarc_trigger.gcs_inbox
  id = "projects/project-f2e57f3d-860e-486f-a44/locations/us-central1/triggers/stack-and-spine-inbox-trigger"
}
