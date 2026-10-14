package com.aquatrace.api.meters;

import com.aquatrace.api.common.ApiException;
import com.aquatrace.api.sites.SiteAuthorizationService;
import com.aquatrace.api.sites.SiteRole;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.api.core.ApiFuture;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MeterService {
    private final Firestore firestore;
    private final SiteAuthorizationService authorizationService;
    private final long timeoutSeconds;

    public MeterService(Firestore firestore, SiteAuthorizationService authorizationService,
                        @Value("${aquatrace.firestore.operation-timeout-seconds:4}") long timeoutSeconds) {
        this.firestore = firestore;
        this.authorizationService = authorizationService;
        this.timeoutSeconds = timeoutSeconds;
    }

    public MeterSummary create(String siteId, String uid, CreateMeterRequest request) {
        authorizationService.requireAnyRole(siteId, uid, SiteRole.ADMIN, SiteRole.PROPERTY_MANAGER);
        DocumentReference meter = firestore.collection("sites").document(siteId)
                .collection("meters").document();
        Map<String, Object> document = new HashMap<>();
        document.put("label", request.label().trim());
        document.put("meterType", request.meterType().name());
        document.put("unitId", request.unitId().trim());
        document.put("unitOfMeasure", request.unitOfMeasure().name());
        document.put("readingMode", request.readingMode().name());
        document.put("expectedIntervalMinutes", request.expectedIntervalMinutes());
        document.put("installationDate", request.installationDate().toString());
        document.put("enabled", true);
        document.put("createdBy", uid);
        document.put("createdAt", FieldValue.serverTimestamp());
        if (request.thresholds() != null) {
            Map<String, Object> thresholds = new HashMap<>();
            MeterThresholdsRequest configured = request.thresholds();
            if (configured.highUsageLitresPerHour() != null) thresholds.put("highUsageLitresPerHour", configured.highUsageLitresPerHour());
            if (configured.lowUsageLitresPerHour() != null) thresholds.put("lowUsageLitresPerHour", configured.lowUsageLitresPerHour());
            if (configured.lowWindowStart() != null || configured.lowWindowEnd() != null) {
                Map<String, Object> window = new HashMap<>();
                if (configured.lowWindowStart() != null) window.put("start", configured.lowWindowStart());
                if (configured.lowWindowEnd() != null) window.put("end", configured.lowWindowEnd());
                thresholds.put("lowUsageWindow", window);
            }
            if (configured.sustainedIntervals() != null) thresholds.put("sustainedIntervals", configured.sustainedIntervals());
            if (!thresholds.isEmpty()) document.put("thresholds", thresholds);
        }
        try {
            meter.set(document).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException exception) {
            throw unavailable();
        }
        return toSummary(meter.getId(), siteId, document, null);
    }

    public List<MeterSummary> list(String siteId, String uid) {
        authorizationService.requireMembership(siteId, uid);
        try {
            QuerySnapshot snapshot = firestore.collection("sites").document(siteId).collection("meters")
                    .orderBy("label").limit(200).get().get(timeoutSeconds, TimeUnit.SECONDS);
            List<ApiFuture<QuerySnapshot>> latestReads = new ArrayList<>();
            for (DocumentSnapshot document : snapshot.getDocuments()) {
                latestReads.add(firestore.collection("sites").document(siteId).collection("meters")
                        .document(document.getId()).collection("readings")
                        .orderBy("timestamp", com.google.cloud.firestore.Query.Direction.DESCENDING)
                        .limit(1).get());
            }
            List<MeterSummary> result = new ArrayList<>();
            for (int index = 0; index < snapshot.getDocuments().size(); index++) {
                DocumentSnapshot document = snapshot.getDocuments().get(index);
                QuerySnapshot readingSnapshot = latestReads.get(index).get(timeoutSeconds, TimeUnit.SECONDS);
                Instant lastReadingAt = readingSnapshot.isEmpty() ? null : toInstant(readingSnapshot.getDocuments().getFirst().getTimestamp("timestamp"));
                result.add(toSummary(document.getId(), siteId, document.getData(), lastReadingAt));
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException exception) {
            throw unavailable();
        }
    }

    private MeterSummary toSummary(String meterId, String siteId, Map<String, Object> document, Instant lastReadingAt) {
        try {
            int expectedIntervalMinutes = ((Number) document.get("expectedIntervalMinutes")).intValue();
            return new MeterSummary(
                    meterId,
                    siteId,
                    (String) document.get("label"),
                    MeterType.valueOf((String) document.get("meterType")),
                    (String) document.get("unitId"),
                    MeasurementUnit.valueOf((String) document.get("unitOfMeasure")),
                    ReadingMode.valueOf((String) document.get("readingMode")),
                    expectedIntervalMinutes,
                    LocalDate.parse((String) document.get("installationDate")),
                    Boolean.TRUE.equals(document.get("enabled")),
                    lastReadingAt,
                    lastReadingAt == null ? "NO_DATA" : lastReadingAt.plusSeconds(expectedIntervalMinutes * 120L).isBefore(Instant.now()) ? "STALE" : "CURRENT");
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_METER_DOCUMENT", "Meter data is misconfigured.");
        }
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FIRESTORE_UNAVAILABLE",
                "The data store could not complete this request. Please retry.");
    }
}

