package com.aquatrace.api.readings;

import com.aquatrace.api.common.ApiException;
import com.aquatrace.api.meters.MeasurementUnit;
import com.aquatrace.api.meters.ReadingMode;
import com.aquatrace.api.sites.SiteAuthorizationService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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

@Service
public class ReadingService {
    private final Firestore firestore;
    private final SiteAuthorizationService authorizationService;
    private final long timeoutSeconds;

    public ReadingService(Firestore firestore, SiteAuthorizationService authorizationService,
            @Value("${aquatrace.firestore.operation-timeout-seconds:4}") long timeoutSeconds) {
        this.firestore = firestore; this.authorizationService = authorizationService; this.timeoutSeconds = timeoutSeconds;
    }

    public ReadingSummary create(String siteId, String meterId, String uid, CreateReadingRequest request) {
        authorizationService.requireMembership(siteId, uid);
        var meterRef = firestore.collection("sites").document(siteId).collection("meters").document(meterId);
        try {
            DocumentSnapshot meter = meterRef.get().get(timeoutSeconds, TimeUnit.SECONDS);
            if (!meter.exists() || !Boolean.TRUE.equals(meter.getBoolean("enabled")))
                throw new ApiException(HttpStatus.NOT_FOUND, "METER_NOT_FOUND", "The meter was not found.");
            MeasurementUnit meterUnit = MeasurementUnit.valueOf(meter.getString("unitOfMeasure"));
            ReadingMode meterMode = ReadingMode.valueOf(meter.getString("readingMode"));
            if (meterUnit != request.unitOfMeasure() || meterMode != request.readingMode())
                throw new ApiException(HttpStatus.BAD_REQUEST, "METER_CONFIGURATION_MISMATCH", "The reading unit and mode must match the registered meter.");
            String readingId = sha256(request.sourceId().trim() + "|" + request.eventId().trim());
            DocumentReference reading = meterRef.collection("readings").document(readingId);
            DocumentSnapshot existing = reading.get().get(timeoutSeconds, TimeUnit.SECONDS);
            Map<String, Object> data = new HashMap<>();
            data.put("siteId", siteId); data.put("meterId", meterId); data.put("eventId", request.eventId().trim());
            data.put("sourceId", request.sourceId().trim()); data.put("timestamp", Timestamp.ofTimeSecondsAndNanos(request.timestamp().getEpochSecond(), request.timestamp().getNano()));
            data.put("value", request.value()); data.put("unitOfMeasure", request.unitOfMeasure().name());
            data.put("readingMode", request.readingMode().name()); data.put("quality", "ACCEPTED");
            data.put("ingestedBy", uid); data.put("createdAt", FieldValue.serverTimestamp());
            if (existing.exists()) {
                if (!sameContent(existing, request)) throw new ApiException(HttpStatus.CONFLICT, "READING_ID_CONFLICT", "This source event ID already exists with different reading data.");
                return toSummary(readingId, existing);
            }
            reading.create(data).get(timeoutSeconds, TimeUnit.SECONDS);
            return new ReadingSummary(readingId, siteId, meterId, request.eventId().trim(), request.sourceId().trim(),
                    request.timestamp(), request.value(), request.unitOfMeasure(), request.readingMode(), "ACCEPTED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (ExecutionException | TimeoutException exception) { throw unavailable(); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_METER_DOCUMENT", "Meter data is misconfigured."); }
    }

    public List<ReadingSummary> list(String siteId, String meterId, String uid, Instant from, Instant to) {
        authorizationService.requireMembership(siteId, uid);
        if (from != null && to != null && from.isAfter(to))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "The from time must be before the to time.");
        Query query = firestore.collection("sites").document(siteId).collection("meters").document(meterId)
                .collection("readings").orderBy("timestamp", Query.Direction.DESCENDING).limit(500);
        if (from != null) query = query.whereGreaterThanOrEqualTo("timestamp", Timestamp.ofTimeSecondsAndNanos(from.getEpochSecond(), from.getNano()));
        if (to != null) query = query.whereLessThanOrEqualTo("timestamp", Timestamp.ofTimeSecondsAndNanos(to.getEpochSecond(), to.getNano()));
        try {
            QuerySnapshot snapshot = query.get().get(timeoutSeconds, TimeUnit.SECONDS);
            List<ReadingSummary> result = new ArrayList<>();
            for (DocumentSnapshot document : snapshot.getDocuments()) result.add(toSummary(document.getId(), document));
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (ExecutionException | TimeoutException exception) { throw unavailable(); }
    }

    private boolean sameContent(DocumentSnapshot old, CreateReadingRequest request) {
        Timestamp timestamp = old.getTimestamp("timestamp");
        return request.eventId().trim().equals(old.getString("eventId")) && request.sourceId().trim().equals(old.getString("sourceId"))
                && timestamp != null && Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()).equals(request.timestamp())
                && java.util.Objects.equals(old.getDouble("value"), request.value())
                && request.unitOfMeasure().name().equals(old.getString("unitOfMeasure"))
                && request.readingMode().name().equals(old.getString("readingMode"));
    }
    private ReadingSummary toSummary(String readingId, DocumentSnapshot document) {
        Timestamp timestamp = document.getTimestamp("timestamp");
        return new ReadingSummary(readingId, document.getString("siteId"), document.getString("meterId"),
                document.getString("eventId"), document.getString("sourceId"), Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()),
                document.getDouble("value"), MeasurementUnit.valueOf(document.getString("unitOfMeasure")),
                ReadingMode.valueOf(document.getString("readingMode")), document.getString("quality"));
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FIRESTORE_UNAVAILABLE", "The data store could not complete this request. Please retry."); }
}
