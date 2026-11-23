output "cloud_run_url" {
  description = "HTTPS endpoint for the IAP-protected Stack and Spine Cloud Run service"
  value       = google_cloud_run_v2_service.app.uri
}

output "artifact_registry_repository" {
  description = "Full Artifact Registry Docker repository path"
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.app.repository_id}"
}

output "media_bucket_name" {
  description = "Private GCS bucket storing cover art and spine photos"
  value       = google_storage_bucket.media.name
}

output "runtime_service_account_email" {
  description = "Least-privilege IAM Service Account attached to Cloud Run"
  value       = google_service_account.cloud_run_runtime.email
}
