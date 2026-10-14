package com.aquatrace.api.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.cloud.FirestoreClient;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration
public class FirebaseAdminConfiguration {

    @Bean(destroyMethod = "delete")
    FirebaseApp firebaseApp(
            @Value("${firebase.project-id}") String projectId,
            @Value("${firebase.storage-bucket:${firebase.project-id}.firebasestorage.app}") String storageBucket,
            @Value("${firebase.emulator-mode:false}") boolean emulatorMode) throws IOException {
        List<FirebaseApp> existing = FirebaseApp.getApps();
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        FirebaseOptions.Builder options = FirebaseOptions.builder().setProjectId(projectId).setStorageBucket(storageBucket);
        if (!emulatorMode) {
            options.setCredentials(GoogleCredentials.getApplicationDefault());
        }
        return FirebaseApp.initializeApp(options.build());
    }

    @Bean
    @DependsOn("firebaseApp")
    FirebaseAuth firebaseAuth(FirebaseApp firebaseApp) {
        return FirebaseAuth.getInstance(firebaseApp);
    }

    @Bean
    @DependsOn("firebaseApp")
    Firestore firestore(FirebaseApp firebaseApp) {
        return FirestoreClient.getFirestore(firebaseApp);
    }
}
