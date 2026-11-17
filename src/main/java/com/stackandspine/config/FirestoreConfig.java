package com.stackandspine.config;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the Google Cloud Firestore client when stackandspine.storage=firestore.
 *
 * Authentication is handled transparently via Application Default Credentials (ADC):
 * - Locally: uses credentials from `gcloud auth application-default login`
 * - On Cloud Run: uses the attached IAM Service Account token from the metadata server
 */
@Configuration
@ConditionalOnProperty(name = "stackandspine.storage", havingValue = "firestore")
public class FirestoreConfig {

    @Bean(destroyMethod = "close")
    public Firestore firestore(
            @Value("${stackandspine.gcp.project-id}") String projectId,
            @Value("${stackandspine.gcp.database-id:(default)}") String databaseId) {
        return FirestoreOptions.getDefaultInstance().toBuilder()
                .setProjectId(projectId)
                .setDatabaseId(databaseId)
                .build()
                .getService();
    }
}
