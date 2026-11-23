variable "project_id" {
  description = "Google Cloud Project ID"
  type        = string
  default     = "project-f2e57f3d-860e-486f-a44"
}

variable "project_number" {
  description = "Google Cloud Project Number (used for globally unique bucket names and Service Agents)"
  type        = string
  default     = "212550439760"
}

variable "region" {
  description = "Primary GCP region for Artifact Registry, Cloud Storage, and Cloud Run"
  type        = string
  default     = "us-central1"
}

variable "firestore_location" {
  description = "Firestore database location (nam5 = US multi-region, Free Tier eligible)"
  type        = string
  default     = "nam5"
}

variable "authorized_user_email" {
  description = "Google Account email granted IAP access to Stack and Spine"
  type        = string
  default     = "joshwever@gmail.com"
}

variable "image_tag" {
  description = "Container image tag in Artifact Registry to deploy to Cloud Run"
  type        = string
  default     = "latest"
}
