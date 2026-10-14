package com.aquatrace.api.alerts;

import com.aquatrace.api.common.ApiException;
import com.aquatrace.api.sites.SiteAuthorizationService;
import com.aquatrace.api.sites.SiteRole;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AlertService {
    private final Firestore firestore;
    private final SiteAuthorizationService authorizationService;
    private final long timeoutSeconds;

    public AlertService(Firestore firestore, SiteAuthorizationService authorizationService,
            @Value("${aquatrace.firestore.operation-timeout-seconds:4}") long timeoutSeconds) {
        this.firestore = firestore; this.authorizationService = authorizationService; this.timeoutSeconds = timeoutSeconds;
    }

    public List<AlertSummary> list(String siteId, String uid, String status) {
        authorizationService.requireMembership(siteId, uid);
        if (status != null && !List.of("OPEN", "ACKNOWLEDGED", "RESOLVED").contains(status))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ALERT_STATUS", "Use OPEN, ACKNOWLEDGED, or RESOLVED.");
        try {
            Query query = firestore.collection("sites").document(siteId).collection("alerts");
            if (status != null) query = query.whereEqualTo("status", status);
            var snapshot = query.orderBy("detectedAt", Query.Direction.DESCENDING).limit(100)
                    .get().get(timeoutSeconds, TimeUnit.SECONDS);
            List<AlertSummary> results = new ArrayList<>();
            for (DocumentSnapshot document : snapshot.getDocuments()) results.add(toSummary(document));
            return results;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (ExecutionException | TimeoutException exception) { throw unavailable(); }
    }

    public AlertSummary transition(String siteId, String alertId, String uid, String target) {
        authorizationService.requireAnyRole(siteId, uid, SiteRole.ADMIN, SiteRole.PROPERTY_MANAGER);
        var reference = firestore.collection("sites").document(siteId).collection("alerts").document(alertId);
        try {
            DocumentSnapshot document = reference.get().get(timeoutSeconds, TimeUnit.SECONDS);
            if (!document.exists()) throw notFound();
            String current = document.getString("status");
            if ("RESOLVED".equals(current) || ("ACKNOWLEDGED".equals(current) && "ACKNOWLEDGED".equals(target)))
                return toSummary(document);
            Map<String, Object> changes = target.equals("RESOLVED")
                    ? Map.of("status", target, "resolvedAt", FieldValue.serverTimestamp(), "acknowledgedBy", uid)
                    : Map.of("status", target, "acknowledgedBy", uid, "acknowledgedAt", FieldValue.serverTimestamp());
            reference.update(changes).get(timeoutSeconds, TimeUnit.SECONDS);
            return toSummary(reference.get().get(timeoutSeconds, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (ExecutionException | TimeoutException exception) { throw unavailable(); }
    }

    @SuppressWarnings("unchecked")
    private AlertSummary toSummary(DocumentSnapshot document) {
        Timestamp timestamp = document.getTimestamp("detectedAt");
        return new AlertSummary(document.getId(), document.getString("siteId"), document.getString("type"),
                document.getString("severity"), document.getString("meterId"),
                timestamp == null ? null : Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()),
                (Map<String, Object>) document.get("observedValue"), (Map<String, Object>) document.get("rule"),
                document.getString("explanation"), document.getString("recommendedInvestigation"),
                document.getString("status"), document.getString("acknowledgedBy"));
    }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "ALERT_NOT_FOUND", "The alert was not found."); }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FIRESTORE_UNAVAILABLE", "The data store could not complete this request. Please retry."); }
}
