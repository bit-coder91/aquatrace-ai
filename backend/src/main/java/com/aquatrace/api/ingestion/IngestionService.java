package com.aquatrace.api.ingestion;

import com.aquatrace.api.common.ApiException;
import com.aquatrace.api.sites.SiteAuthorizationService;
import com.aquatrace.api.sites.SiteRole;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.StorageClient;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class IngestionService {
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private final Firestore firestore;
    private final FirebaseApp firebaseApp;
    private final SiteAuthorizationService authorizationService;
    private final long timeoutSeconds;

    public IngestionService(Firestore firestore, FirebaseApp firebaseApp,
            SiteAuthorizationService authorizationService,
            @Value("${aquatrace.firestore.operation-timeout-seconds:4}") long timeoutSeconds) {
        this.firestore = firestore;
        this.firebaseApp = firebaseApp;
        this.authorizationService = authorizationService;
        this.timeoutSeconds = timeoutSeconds;
    }

    public IngestionJobSummary upload(String siteId, String uid, MultipartFile file) {
        authorizationService.requireAnyRole(siteId, uid, SiteRole.ADMIN, SiteRole.PROPERTY_MANAGER);
        if (file == null || file.isEmpty()) throw badRequest("EMPTY_UPLOAD", "Choose a non-empty CSV or JSON file.");
        if (file.getSize() > MAX_FILE_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Uploads are limited to 10 MiB.");
        String filename = safeFilename(file.getOriginalFilename());
        String lowerName = filename.toLowerCase(java.util.Locale.ROOT);
        if (!(lowerName.endsWith(".csv") || lowerName.endsWith(".json") || lowerName.endsWith(".jsonl"))) {
            throw badRequest("UNSUPPORTED_FILE_TYPE", "Upload a .csv, .json, or .jsonl file.");
        }
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException exception) { throw unavailable(); }
        String checksum = sha256(bytes);
        var job = firestore.collection("ingestionJobs").document();
        String storagePath = "sites/" + siteId + "/uploads/" + job.getId() + "/" + filename;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("siteId", siteId); metadata.put("uploadedBy", uid); metadata.put("fileName", filename);
        metadata.put("fileSizeBytes", bytes.length); metadata.put("checksumSha256", checksum);
        metadata.put("storagePath", storagePath); metadata.put("status", "UPLOADING");
        metadata.put("createdAt", FieldValue.serverTimestamp()); metadata.put("updatedAt", FieldValue.serverTimestamp());
        try {
            job.set(metadata).get(timeoutSeconds, TimeUnit.SECONDS);
            var bucket = StorageClient.getInstance(firebaseApp).bucket();
            if (bucket == null) throw new IllegalStateException("Firebase Storage bucket is not configured");
            bucket.create(storagePath, bytes, contentType(lowerName));
            job.update(Map.of("status", "UPLOADED", "updatedAt", FieldValue.serverTimestamp()))
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            DocumentSnapshot saved = job.get().get(timeoutSeconds, TimeUnit.SECONDS);
            return toSummary(saved);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markFailed(job, "Upload interrupted");
            throw unavailable();
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            markFailed(job, "File storage failed");
            throw unavailable();
        }
    }

    public List<IngestionJobSummary> list(String siteId, String uid) {
        authorizationService.requireMembership(siteId, uid);
        try {
            QuerySnapshot snapshot = firestore.collection("ingestionJobs").whereEqualTo("siteId", siteId)
                    .orderBy("createdAt", com.google.cloud.firestore.Query.Direction.DESCENDING)
                    .limit(100).get().get(timeoutSeconds, TimeUnit.SECONDS);
            List<IngestionJobSummary> results = new ArrayList<>();
            for (DocumentSnapshot document : snapshot.getDocuments()) results.add(toSummary(document));
            return results;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (ExecutionException | TimeoutException exception) { throw unavailable(); }
    }

    public IngestionJobSummary get(String siteId, String jobId, String uid) {
        authorizationService.requireMembership(siteId, uid);
        try {
            DocumentSnapshot document = firestore.collection("ingestionJobs").document(jobId)
                    .get().get(timeoutSeconds, TimeUnit.SECONDS);
            if (!document.exists() || !siteId.equals(document.getString("siteId")))
                throw new ApiException(HttpStatus.NOT_FOUND, "INGESTION_JOB_NOT_FOUND", "The ingestion job was not found.");
            return toSummary(document);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (ExecutionException | TimeoutException exception) { throw unavailable(); }
    }

    private IngestionJobSummary toSummary(DocumentSnapshot document) {
        return new IngestionJobSummary(document.getId(), document.getString("siteId"), document.getString("status"),
                document.getString("fileName"), number(document, "fileSizeBytes"), document.getString("checksumSha256"),
                document.getCreateTime() == null ? null : document.getCreateTime().toDate().toInstant(),
                optionalNumber(document, "inputRows"), optionalNumber(document, "acceptedRows"), optionalNumber(document, "rejectedRows"));
    }

    private void markFailed(com.google.cloud.firestore.DocumentReference job, String reason) {
        try { job.update(Map.of("status", "FAILED", "failureReason", reason, "updatedAt", FieldValue.serverTimestamp())); }
        catch (RuntimeException ignored) { /* best effort after the primary storage failure */ }
    }
    private static long number(DocumentSnapshot doc, String key) { Number n = doc.get(key, Number.class); return n == null ? 0 : n.longValue(); }
    private static Integer optionalNumber(DocumentSnapshot doc, String key) { Number n = doc.get(key, Number.class); return n == null ? null : n.intValue(); }
    private static String safeFilename(String value) {
        if (value == null || value.isBlank()) return "upload.csv";
        String leaf = value.replace('\\', '/'); leaf = leaf.substring(leaf.lastIndexOf('/') + 1);
        return leaf.replaceAll("[^A-Za-z0-9._-]", "_").substring(0, Math.min(leaf.length(), 128));
    }
    private static String contentType(String filename) { return filename.endsWith(".csv") ? "text/csv" : "application/json"; }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static ApiException badRequest(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INGESTION_STORE_UNAVAILABLE", "The upload could not be stored. Please retry."); }
}
