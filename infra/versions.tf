terraform {
  required_version = ">= 1.6.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }

  # Production tip: Once you want shared/remote Terraform state in GCP,
  # create a versioned GCS bucket and uncomment this block:
  # backend "gcs" {
  #   bucket = "stack-and-spine-tfstate-212550439760"
  #   prefix = "terraform/state"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
